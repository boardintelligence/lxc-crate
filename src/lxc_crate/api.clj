(ns lxc-crate.api
  (:require
   [pallet.algo.fsmop :as fsmop]
   [pallet.node :as node]
   [pallet.configure :as configure]
   [pallet.compute :as compute]
   [pallet.api :as api]
   [pallet-nodelist-helpers :as helpers]
   [lxc-crate.lxc :as lxc]))

(defn host-is-lxc-server?
  "Check if a host is a LXC server (as understood by the lxc-create)"
  [hostname]
  (helpers/host-has-phase? hostname :create-lxc-container))

(defn host-is-lxc-image-server?
  "Check if a host is an image server (as understood by the lxc-create)"
  [hostname]
  (helpers/host-has-phase? hostname :setup-image-server))

(defn boot-up-fresh-tmp-container
  "Boot up our known pre-defined lxc container"
  [image-server spec-kw image-specs]
  (helpers/ensure-nodelist-bindings)
  (when-not (host-is-lxc-image-server? image-server)
    (throw (IllegalArgumentException. (format "%s is not an image server!" image-server))))
  (let [image-server-conf (get-in helpers/*nodelist-hosts-config* [image-server :image-server])
        tmp-hostname (:tmp-hostname image-server-conf)]
    (println "Bring up minimal minimal base container..")
    (helpers/run-one-plan-fn image-server (api/plan-fn (lxc/create-lxc-container :overwrite? true))
                             {:container-for tmp-hostname
                              :override-spec spec-kw
                              :tmp-container-run true
                              :image-specs image-specs})
    (println "Waiting for container to spin up (10s)..")
    (Thread/sleep (* 10 1000))
    (println "Performing minimal image prep work")
    (helpers/run-one-plan-fn tmp-hostname (api/plan-fn (lxc/minimal-image-prep)))
    (println "tmp container up - connect to it using:" tmp-hostname)))

(defn run-setup-fn-in-tmp-container
  "Run a given image-spec's setup-fn in the tmp container"
  [image-server spec-kw image-specs]
  (helpers/ensure-nodelist-bindings)
  (when-not (host-is-lxc-image-server? image-server)
    (throw (IllegalArgumentException. (format "%s is not an image server!" image-server))))
  (let [image-server-conf (get-in helpers/*nodelist-hosts-config* [image-server :image-server])
        tmp-hostname (:tmp-hostname image-server-conf)]
    (println "Run setup-fn..")
    (helpers/run-one-plan-fn tmp-hostname lxc/run-setup-fn-in-tmp-container {:override-spec spec-kw
                                                                             :image-specs image-specs})
    (println "Setup-fn finished.")))

(defn halt-container
  "Halt a container"
  [lxc-server container]
  (helpers/ensure-nodelist-bindings)
  (when-not (host-is-lxc-server? lxc-server)
    (throw (IllegalArgumentException. (format "%s is not an lxc server!" lxc-server))))
  (println "Halt container..")
  (helpers/run-one-plan-fn lxc-server lxc/halt-container {:container container})
  (println "Container halted."))

(defn start-container
  "Start a container"
  [lxc-server container]
  (helpers/ensure-nodelist-bindings)
  (when-not (host-is-lxc-server? lxc-server)
    (throw (IllegalArgumentException. (format "%s is not an lxc server!" lxc-server))))
  (println "Start container..")
  (helpers/run-one-plan-fn lxc-server lxc/start-container {:container container})
  (println "Container started."))

(defn halt-tmp-container
  "Halt the tmp container"
  [image-server]
  (helpers/ensure-nodelist-bindings)
  (when-not (host-is-lxc-image-server? image-server)
    (throw (IllegalArgumentException. (format "%s is not an image server!" image-server))))
  (println "Halt tmp container..")
  (let [image-server-conf (get-in helpers/*nodelist-hosts-config* [image-server :image-server])
        tmp-hostname (:tmp-hostname image-server-conf)]
    (helpers/run-one-plan-fn tmp-hostname lxc/halt-tmp-container)
    (println "Waiting for container to halt (70s)..")
    (Thread/sleep (* 70 1000))
    (println "tmp container halted.")))

(defn snapshot-image-of-container
  "Take a snapshot of a container."
  [lxc-server container-name image-server image-name]
  (helpers/ensure-nodelist-bindings)
  (when-not (host-is-lxc-image-server? image-server)
    (throw (IllegalArgumentException. (format "%s is not an image server!" image-server))))
  (halt-container lxc-server container-name)
  (println "Snapshot container image (this could take a while depending on size of delta to transfer)..")
  (helpers/run-one-plan-fn lxc-server lxc/snapshot-container {:container-name container-name
                                                              :image-server image-server
                                                              :image-name image-name})
  (println "Finished container snapshot for" container-name)
  (start-container lxc-server container-name))

(defn snapshot-image-of-tmp-container
  "Take a snapshot of the tmp container."
  [image-server spec-kw image-specs]
  (let [container-name (get-in helpers/*nodelist-hosts-config* [image-server :image-server :tmp-hostname])
        image-name (get-in image-specs [spec-kw :image-name])]
    (snapshot-image-of-container image-server container-name image-server image-name)))

(defn destroy-tmp-container
  "Destroy the tmp container."
  [image-server]
  (helpers/ensure-nodelist-bindings)
  (when-not (host-is-lxc-image-server? image-server)
    (throw (IllegalArgumentException. (format "%s is not an image server!" image-server))))
  (println "Destroy the tmp container..")
  (helpers/run-one-plan-fn image-server lxc/destroy-tmp-container)
  (println "tmp container destroyed."))

(defn create-lxc-image-all-steps
  [image-server spec-kw image-specs]
  (boot-up-fresh-tmp-container image-server spec-kw image-specs)
  (run-setup-fn-in-tmp-container image-server spec-kw image-specs)
  (halt-tmp-container image-server)
  (snapshot-image-of-tmp-container image-server spec-kw image-specs)
  (destroy-tmp-container image-server))

(defn- root-from-image-spec
  [image-spec]
  (let [ssh-public-key-path (:root-key-pub image-spec)
        ssh-private-key-path (:root-key-priv image-spec)
        passphrase (:root-key-passphrase image-spec)]
    (api/make-user "root"
                   :public-key (slurp ssh-public-key-path)
                   :private-key (slurp ssh-private-key-path)
                   :passphrase passphrase
                   :no-sudo true)))

(defn setup-container-admin-user
  "Setup admin user for container"
  [hostname image-specs]
  (helpers/ensure-nodelist-bindings)
  (let [container-config (get helpers/*nodelist-hosts-config* hostname)
        root-from-spec (root-from-image-spec ((:image-spec container-config) image-specs))]
    (helpers/run-one-plan-fn hostname root-from-spec lxc/setup-container-admin-user {})))

(defn create-lxc-container
  "Create a lxc container on a given lxc server."
  [hostname image-specs]
  (helpers/ensure-nodelist-bindings)
  (let [container-config (get helpers/*nodelist-hosts-config* hostname)
        lxc-server (:lxc-server container-config)
        autostart (:lxc-autostart container-config)]
    (when-not (host-is-lxc-server? lxc-server)
      (throw (IllegalArgumentException. (format "%s is not an LXC server!" lxc-server))))
    (println (format "Create container %s (on server %s).."
                     hostname lxc-server))

    (helpers/lift-one-node-and-phase lxc-server :create-lxc-container {:container-for hostname
                                                                       :image-specs image-specs
                                                                       :autostart autostart})
    (println "Waiting 10s for container to boot..")
    (println (Thread/sleep (* 10 1000)))
    (println "Setting up container admin user..")
    (setup-container-admin-user hostname image-specs)
    (println (format "Container created for %s." hostname))))
