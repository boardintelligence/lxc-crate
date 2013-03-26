(ns lxc-crate.lxc
  "Crate with functions for setting up and configuring LXC servers and containers"
  (:require clojure.string
            [pallet.actions :as actions]
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
                         :template "lxc/ovsup"
                         :values {:bridge bridge})
    (actions/remote-file "/etc/lxc/ovsdown"
                         :mode "0755"
                         :literal true
                         :template "lxc/ovdown"
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

(defplan run-setup-fn-in-tmp-container
  []
  (let [tmp-hostname (crate/target-name)
        host-config (env/get-environment [:host-config tmp-hostname])
        image-spec (env/get-environment [:image-specs (:base-image host-config)])
        ;;root-auth-key-path (get image-spec :root-auth-key)
        ]

    (when (:setup-fn image-spec)
      ((:setup-fn image-spec)))
    ;; then install the right root ssh key
    ;; (actions/exec-checked-script
    ;;  "Remove tmp ssh key"
    ;;  ("rm -f /root/.ssh/authorized_keys"))
    ;; (ssh-key/authorize-key "root" (slurp root-auth-key-path))
    ))

(defplan halt-tmp-container
  "Halt tmp container."
  []
  (actions/exec-checked-script
   "Halt tmp instance."
   ("apt-get install at")
   (pipe ("echo halt")
         ("at -M now + 1 minute"))))

(defplan snapshot-tmp-container
  []
  (let [server (crate/target-name)
        tmp-hostname (env/get-environment [:host-config server :image-server :tmp-hostname])
        spec-name (name (env/get-environment [:host-config tmp-hostname :base-image]))]
    (println "Taking snapshot of image..")
    (take-image-snapshot tmp-hostname spec-name)))

(defplan destroy-tmp-container
  []
  (let [server (crate/target-name)
        tmp-hostname (env/get-environment [:host-config server :image-server :tmp-hostname])]
    (println "Destroying tmp container..")
    (destroy-container tmp-hostname)))

(defplan create-lxc-container
  "Create a given lxc-container"
  [ & {:keys [overwrite?]}]
  (let [server (crate/target-name)
        image-specs (env/get-environment [:image-specs])
        container-hostname (env/get-environment [:container-for])
        container-config (env/get-environment [:host-config container-hostname])
        spec (get image-specs (:base-image container-config))
        ssh-public-key (env/get-environment [:host-config container-hostname :admin-user :ssh-public-key-path])
        remote-ssh-key-path (format "/tmp/%s.pub" container-hostname)
        image-dir (format "/var/lib/lxc/%s" container-hostname)
        remote-config-file (format "/etc/lxc/lxc-%s.conf" container-hostname)
        config-local-path (get-in spec [:lxc-create :f])
        local-template-file (get-in spec [:lxc-create :template-script])]
    (prn container-config)
    (actions/remote-file remote-ssh-key-path
                         :mode "0644"
                         :literal true
                         :local-file ssh-public-key)
    (actions/remote-file remote-config-file
                         :mode "0644"
                         :literal true
                         :template config-local-path
                         :values {:mac (:mac container-config)})

    (when local-template-file
      (actions/remote-file (format "/usr/share/lxc/templates/lxc-%s"
                                   (get-in spec [:lxc-create :template]))
                           :mode "0755"
                           :literal true
                           :local-file local-template-file))

    (when overwrite?
      (actions/exec-checked-script
       "Ensure old container not in the way"
       ("lxc-stop -n" ~container-hostname)
       ("sleep 5")
       ("rm -rf" ~image-dir)))

    ;; TODO: refactor to generate a string of args here and pass the args
    ;; more generic
    (create-base-container container-hostname
                           remote-config-file
                           (get-in spec [:lxc-create :t])
                           (get-in spec [:lxc-create :template-args :release])
                           remote-ssh-key-path)

    (when-let [image-url (:image-url spec)]
      (let [config-remote-path (format "%s/config" image-dir)
            backup-config (format "/tmp/%s-lxc-config" container-hostname)]
        (actions/exec-checked-script
         "Backup config file"
         ("cp" ~config-remote-path ~backup-config)
         ("rm -rf" ~image-dir)
         ("mkdir -p" ~image-dir)
         ("rdiff-backup --restore-as-of now" ~image-url ~image-dir)
         ("cp" ~backup-config ~config-remote-path))))

    ;; spin up container
    (boot-up-container container-hostname)))
