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

(defn create-lxc-image
  "Create a lxc image on a given image server."
  [image-server image-spec-name image-spec]
  (helpers/ensure-nodelist-bindings)
  (when-not (host-is-lxc-image-server? image-server)
    (throw (IllegalArgumentException. (format "%s is not an image server!" image-server))))
  (let [image-server-conf (get-in helpers/*nodelist-hosts-config* [image-server :image-server])
        tmp-hostname (:tmp-hostname image-server-conf)]

    (println "create-lxc-image:")
    (println "Running step 1 - create minimal base container..")
    (let [result (helpers/run-one-plan-fn image-server lxc/create-lxc-image-step1 {:image-spec image-spec})]
      (when (fsmop/failed? result)
        (throw (IllegalStateException. "Failed to create image (step1)!"))))

    (println "Waiting for container to spin up (10s)..")
    (Thread/sleep (* 10 1000)) ;; give it a chance to spin up

    (println "Running step 2 - image setup function..")
    (let [result (helpers/run-one-plan-fn tmp-hostname
                                          ;;tmp-admin-user
                                          lxc/create-lxc-image-step2
                                          {:image-spec image-spec})]
      (when (fsmop/failed? result)
        (throw (IllegalStateException. "Failed to create image (step2)!"))))

    (println "Waiting for container to halt in 1min (and 10s)..")
    (Thread/sleep (* 70 1000))
    (println "Running step 3 - take image snapshot and destroy tmp container..")
    (let [result (helpers/run-one-plan-fn image-server lxc/create-lxc-image-step3 {:image-spec image-spec
                                                                                   :image-spec-name image-spec-name})]
      (when (fsmop/failed? result)
        (throw (IllegalStateException. "Failed to create image (step3)!"))))
    (println "Finished - lxc image created")))

;;;;;;; for dnsmasq crate

;; (defn host-is-lxc-dhcp-server?
;;   "Check if a host is a DHCP server (as understood by the lxc-create)"
;;   [hostname]
;;   (helpers/host-has-phase? hostname :update-dhcp-config))

;; (defn update-dhcp-config
;;   "Update the dhcp hosts file on DHCP server for private LAN."
;;   [dhcp-server]
;;   (helpers/ensure-nodelist-bindings)
;;   (when-not (host-is-dhcp-server? dhcp-server)
;;     (throw (IllegalArgumentException. (format "%s is not a dhcp server!" dhcp-server))))
;;   (let [result (helpers/lift-one-node-and-phase dhcp-server :update-dhcp-config)]
;;     (when (fsmop/failed? result)
;;       (throw (IllegalStateException. "Failed to update dhcp config!")))
;;     result))
