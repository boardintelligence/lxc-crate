(ns lxc-crate.specs
  "Server and group specs for working with LXC servers and containers"
  (:require
   [pallet.api :as api]
   [lxc-crate.lxc :as lxc]))

(def
  ^{:doc "Server spec for a LXC server (host)."}
  lxc-server
  (api/server-spec
   :phases
   {:configure (api/plan-fn (lxc/setup-lxc))
    :create-lxc-container (api/plan-fn (lxc/create-lxc-container))}))

(def
  ^{:doc "Server spec for a LXC image server (host)."}
  lxc-image-server
  (api/server-spec
   :phases
   {:configure (api/plan-fn (lxc/setup-image-server))
    :snapshot-tmp-container (api/plan-fn (lxc/snapshot-tmp-container))}))

(def
  ^{:doc "Spec for a LXC container."}
  lxc-container
  (api/server-spec
   :phases
   {;;:firstboot (api/plan-fn (kvm/setup-guest-vm-firstboot))
    }))
