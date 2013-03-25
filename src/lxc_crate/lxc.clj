(ns lxc-crate.lxc
  "Crate with functions for setting up and configuring LXC servers and containers"
  (:require [pallet.actions :as actions]
            [pallet.utils :as utils]
            [pallet.crate :as crate]
            [pallet.environment :as env]
            [pallet.crate.ssh-key :as ssh-key]
            [pallet.crate :refer [defplan]]))

(defplan install-packages
  "Install all needed packages for LXC."
  []
  (actions/package "lxc"))

(defplan install-lxc-defaults
  "Install our lxc defaults, don't want default lxcbr0 bridge."
  []
  (actions/remote-file "/etc/default/lxc"
                       :local-file (utils/resource-path "lxc/lxc-defaults")
                       :literal true)
  (actions/exec-checked-script
   "Restart LXC net"
   ("service lxc-net restart")))

(defplan remove-dnsmasq-file
  "Remove default dnsmasq file for lxc"
  []
  (actions/exec-checked-script
   "Delete dnsmasq file for lxc"
   ("rm -f /etc/dnsmasq.d/lxc")))

(defplan relink-var-lib-lxc-to-home
  "We prefer to put the containers on /home for space issues, use symlink."
  []
  (actions/exec-checked-script
   "Link lxc container location to /home/lxc"
   ("rm -rf /var/lib/lxc")
   ("mkdir -p /home/lxc")
   ("chmod 755 /home/lxc")
   ("ln -s /home/lxc /var/lib/lxc")))

(defplan install-ovs-scripts
  "Install helper scripts we need to connect containers to OVS."
  []
  (let [node-hostname (crate/target-name)
        bridge (env/get-environment [:host-config node-hostname :ovs :bridge])]
    (actions/remote-file "/etc/lxc/ovsup"
                         :mode "0755"
                         :literal true
                         :template (utils/resource-path "lxc/ovsup")
                         :values {:bridge bridge})
    (actions/remote-file "/etc/lxc/ovsdown"
                         :mode "0755"
                         :literal true
                         :template (utils/resource-path "lxc/ovdown")
                         :values {:bridge bridge})))

(defplan setup-lxc
  "Perform all setup needed to make a host an LXC container server."
  []
  (install-packages)
  (install-lxc-defaults)
  (remove-dnsmasq-file)
  (relink-var-lib-lxc-to-home)
  (install-ovs-scripts))

(defplan create-lxc-container
  "Create a new LXC container"
  []
  )

(defplan setup-image-server
  "Perform all setup needed to make a host an LXC image server."
  []
  (let [node-hostname (crate/target-name)
        mac (env/get-environment [:host-config node-hostname :image-server :tmp-mac])]
    (actions/package "rdiff-backup")
    (actions/exec-checked-script
     "Make sure we have directories needed for image server"
     ("mkdir -p /home/image-server/images")
     ("mkdir -p /home/image-server/ssh-keys")
     ("mkdir -p /home/image-server/etc"))
    ;; TODO: install known config file into etc used to spin up temp containers
    (actions/remote-file "/etc/lxc/lxc-tmp.conf"
                         :mode "0644"
                         :literal true
                         :template "lxc/lxc-tmp.conf"
                         :values {:mac mac})))

(defplan create-base-container
  "Create a base container, just the lxc-create step."
  [name conf-file template release auth-key-path]
  (actions/exec-checked-script
   "Create a base container via lxc-create"
   ("lxc-create -n" ~name "-f" ~conf-file "-t" ~template "-- --release" ~release "--auth-key" ~auth-key-path)))

(defplan boot-up-container
  "Boot a given container"
  [name]
  (actions/exec-checked-script
   "Start lxc container"
   ("lxc-start -d -n" ~name)))

(defplan halt-container
  "Halt a given container"
  [name]
  (actions/exec-checked-script
   "Start lxc container"
   (if-not (= @(pipe ("lxc-info -n" ~name)
                   ("grep RUNNING")) "")
     ("lxc-halt -n" ~name))))

(defplan take-image-snapshot
  "Take a snapshot of the image of a given container."
  [hostname spec-name]
  (let [image-dir (format "/var/lib/lxc/%s" hostname)
        backup-dir (format "/home/image-server/images/%s" spec-name)]
    (actions/exec-checked-script
     "Take a snapshot of a given image"
     ("rdiff-backup" ~image-dir ~backup-dir))))

(defplan destroy-container
  "Destroy a given container"
  [name]
  (actions/exec-checked-script
   "Start lxc container"
   ("lxc-destroy -n" ~name "-f")))

(defplan create-lxc-image-step1
  "Create a LXC container image according to a given spec - step1.
  Step 1 will create the base container and start it up."
  []
  (let [server (crate/target-name)
        tmp-hostname (env/get-environment [:host-config server :image-server :tmp-hostname])
        ssh-public-key (env/get-environment [:host-config tmp-hostname :admin-user :ssh-public-key-path])
        image-spec (env/get-environment [:image-spec])
        remote-ssh-key-path (format "/home/image-server/ssh-keys/%s.pub" tmp-hostname)
        remote-image-dir (format "/var/lib/lxc/%s" tmp-hostname)]
    (actions/remote-file remote-ssh-key-path
                         :mode "0644"
                         :literal true
                         :local-file ssh-public-key)
    (actions/exec-checked-script
     "Ensure old container not in the way"
     ("lxc-stop -n" ~tmp-hostname)
     ("sleep 5")
     ("rm -rf" ~remote-image-dir))
    (create-base-container tmp-hostname
                           "/etc/lxc/lxc-tmp.conf"
                           (get-in image-spec [:lxc-create :template])
                           (get-in image-spec [:lxc-create :release])
                           remote-ssh-key-path)

    (boot-up-container tmp-hostname)))

(defplan create-lxc-image-step2
  "Create a LXC container image according to a given spec - step2.
  Step 2 will run the image setup function in the tmp container.
  It will also install the wanted root ssh key."
  []
  (let [tmp-hostname (crate/target-name)
        image-spec (env/get-environment [:image-spec])
        root-auth-key-path (env/get-environment [:image-spec :root-auth-key])]

    ;; first run the setup-fn
    (when (:setup-fn image-spec)
      ((:setup-fn image-spec)))

    ;; then install the right root ssh key
    (actions/exec-checked-script
     "Remove tmp ssh key"
     ("rm -f /root/.ssh/authorized_keys"))
    (ssh-key/authorize-key "root" (slurp root-auth-key-path))

    (actions/exec-checked-script
     "Halt tmp instance."
     ("apt-get install at")
     (pipe ("echo halt")
           ("at -M now + 1 minute")))))

(defplan create-lxc-image-step3
  "Create a LXC container image according to a given spec - step3.
  Step 3 will take a snapshot of the tmp container image and then destroy it."
  []
  (let [server (crate/target-name)
        tmp-hostname (env/get-environment [:host-config server :image-server :tmp-hostname])
        spec-name (env/get-environment [:image-spec-name])]
    (println "Taking snapshot of image..")
    (take-image-snapshot tmp-hostname spec-name)
    (println "Destroying tmp container..")
    (destroy-container tmp-hostname)))

;; (defplan create-guest-vm
;;   "TODO: implement"
;;   []
;;   )
