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
  (helpers/host-has-phase? hostname :create-lxc-image-step1))

(defn boot-up-fresh-tmp-container
  "Boot up our known pre-defined lxc container"
  [image-server spec-kw spec]
  (helpers/ensure-nodelist-bindings)
  (when-not (host-is-lxc-image-server? image-server)
    (throw (IllegalArgumentException. (format "%s is not an image server!" image-server))))
  (let [image-server-conf (get-in helpers/*nodelist-hosts-config* [image-server :image-server])
        tmp-hostname (:tmp-hostname image-server-conf)]
    (println "Bring up minimal minimal base container..")
    (let [result (helpers/run-one-plan-fn image-server lxc/boot-up-fresh-tmp-container {:image-spec spec})]
      (when (fsmop/failed? result)
        (throw (IllegalStateException. "Failed to bring up fresh tmp container!")))
      (println "Waiting for container to spin up (10s)..")
      (Thread/sleep (* 10 1000))
      (println "tmp container up - connect to it using:" tmp-hostname))))

(defn run-setup-fn-in-tmp-container
  "Run a given image-spec's setup-fn in the tmp container"
  [image-server spec-kw spec]
  (helpers/ensure-nodelist-bindings)
  (when-not (host-is-lxc-image-server? image-server)
    (throw (IllegalArgumentException. (format "%s is not an image server!" image-server))))
  (let [image-server-conf (get-in helpers/*nodelist-hosts-config* [image-server :image-server])
        tmp-hostname (:tmp-hostname image-server-conf)]
    (println "Run setup-fn..")
    (let [result (helpers/run-one-plan-fn tmp-hostname lxc/run-setup-fn-in-tmp-container {:image-spec spec})]
      (when (fsmop/failed? result)
        (throw (IllegalStateException. "Failed to run setup-fn in tmp container!")))
      (println "Setup-fn finished."))))

(defn halt-tmp-container
  "Halt the tmp container"
  [image-server spec-kw spec]
  (helpers/ensure-nodelist-bindings)
  (when-not (host-is-lxc-image-server? image-server)
    (throw (IllegalArgumentException. (format "%s is not an image server!" image-server))))
  (let [image-server-conf (get-in helpers/*nodelist-hosts-config* [image-server :image-server])
        tmp-hostname (:tmp-hostname image-server-conf)]
    (println "Halt tmp container..")
    (let [result (helpers/run-one-plan-fn tmp-hostname lxc/halt-tmp-container)]
      (when (fsmop/failed? result)
        (throw (IllegalStateException. "Failed to halt tmp container!")))
      (println "Waiting for container to halt (70s)..")
      (Thread/sleep (* 70 1000))
      (println "tmp container halted."))))

(defn snapshot-image-of-tmp-container
  "Take a snapshot of the tmp container."
  [image-server spec-kw spec]
  (helpers/ensure-nodelist-bindings)
  (when-not (host-is-lxc-image-server? image-server)
    (throw (IllegalArgumentException. (format "%s is not an image server!" image-server))))
  (let [image-server-conf (get-in helpers/*nodelist-hosts-config* [image-server :image-server])
        tmp-hostname (:tmp-hostname image-server-conf)]
    (println "Snapshot tmp container..")
    (let [result (helpers/run-one-plan-fn image-server lxc/snapshot-tmp-container {:image-spec spec
                                                                                   :image-spec-name (name spec-kw)})]
      (when (fsmop/failed? result)
        (throw (IllegalStateException. "Failed to snapshot tmp container!")))
      (println "tmp container snapshot for spec" spec-kw))))

(defn destroy-tmp-container
  "Destroy the tmp container."
  [image-server spec-kw spec]
  (helpers/ensure-nodelist-bindings)
  (when-not (host-is-lxc-image-server? image-server)
    (throw (IllegalArgumentException. (format "%s is not an image server!" image-server))))
  (let [image-server-conf (get-in helpers/*nodelist-hosts-config* [image-server :image-server])
        tmp-hostname (:tmp-hostname image-server-conf)]
    (println "Destroy the tmp container..")
    (let [result (helpers/run-one-plan-fn image-server lxc/destroy-tmp-container {:image-spec spec})]
      (when (fsmop/failed? result)
        (throw (IllegalStateException. "Failed to destroy tmp container!")))
      (println "tmp container destroyed."))))

(defn create-lxc-image-all-steps
  [image-server spec-kw specs]
  (boot-up-fresh-tmp-container image-server spec-kw specs)
  (run-setup-fn-in-tmp-container image-server spec-kw specs)
  (halt-tmp-container image-server spec-kw specs)
  (snapshot-image-of-tmp-container image-server spec-kw specs)
  (destroy-tmp-container image-server spec-kw specs))

(defn create-lxc-container
  "Create a lxc container on a given lxc server."
  [hostname spec-name spec]
  (helpers/ensure-nodelist-bindings)
  (let [lxc-server (get-in helpers/*nodelist-hosts-config* [hostname :lxc-server])]
    (when-not (host-is-lxc-server? lxc-server)
      (throw (IllegalArgumentException. (format "%s is not an LXC server!" lxc-server))))))
