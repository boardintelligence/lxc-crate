(ns lxc-crate.lxc
  "Crate with functions for setting up and configuring LXC servers and containers"
  (:require clojure.string
            [pallet.actions :as actions]
            [pallet.action :refer [with-action-options]]
            [pallet.utils :as utils]
            [pallet.crate :as crate]
            [pallet.environment :as env]
            [pallet.crate.ssh-key :as ssh-key]
            [pallet.crate.automated-admin-user :as admin-user]
            [pallet.crate :refer [defplan]]))

(defplan install-packages
  "Install all needed packages for LXC."
  []
  (actions/package "lxc")
  (actions/package "rdiff-backup"))

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
  "Create a base container, just the lxc-create step.
  Note: we assume that with the args and the template used after running this
  we have a container with a known root user ssh key approved.
  See resources/lxc/lxc-root-ubuntu for an example template.
  If :auth-key-path key is given it's added to the end of the template-args,
  which works well with the ubuntu style tempaltes. For other targets work is
  needed."
  [name conf-file create-conf & {:keys [auth-key-path]}]
  (let [template (:t create-conf)
        template-args (reduce str "" (map (fn [[k v]] (format " --%s %s" (name k) v)) (:template-args create-conf)))
        template-args (if auth-key-path
                        (str template-args (format " --auth-key %s" auth-key-path))
                        template-args)]
    (actions/exec-checked-script
     "Create a base container via lxc-create"
     ("lxc-create -n" ~name "-f" ~conf-file "-t" ~template "--" ~template-args))))

(defplan boot-up-container
  "Boot a given container"
  [name]
  (actions/exec-checked-script
   "Start lxc container"
   ("lxc-start -d -n" ~name)))

(defplan minimal-image-prep
  []
  (with-action-options {:always-before #{actions/package-manager actions/package actions/minimal-packages}}
    (actions/package-manager :update)
    (actions/exec-script "apt-get install -q -y aptitude software-properties-common"))
  (actions/minimal-packages))

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
     ("rdiff-backup --preserve-numerical-ids" ~image-dir ~backup-dir))))

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
        spec-kw (or (env/get-environment [:override-spec])
                    (:base-image host-config))
        image-spec (env/get-environment [:image-specs spec-kw])
        root-key-pub (get image-spec :root-key-pub)]

    (when (:setup-fn image-spec)
      ((:setup-fn image-spec) image-spec host-config))

    (actions/exec-checked-script
     "Remove tmp ssh key"
     ("rm -f /root/.ssh/authorized_keys"))
    (ssh-key/authorize-key "root" (slurp root-key-pub))))

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
        spec-kw (or (env/get-environment [:override-spec])
                    (env/get-environment [:host-config tmp-hostname :base-image]))
        spec-name (name spec-kw)]
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
        spec-kw (env/get-environment [:override-spec] (:base-image container-config))
        spec (get image-specs spec-kw)
        tmp-run (env/get-environment [:tmp-container-run] nil)
        ssh-public-key (env/get-environment [:host-config container-hostname :admin-user :ssh-public-key-path])
        remote-ssh-key-path (format "/tmp/%s.pub" container-hostname)
        image-dir (format "/var/lib/lxc/%s" container-hostname)
        remote-config-file (format "/etc/lxc/lxc-%s.conf" container-hostname)
        config-local-path (get-in spec [:lxc-create :f])
        local-template-file (get-in spec [:lxc-create :template-script])]

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

    (create-base-container container-hostname
                           remote-config-file
                           (get spec :lxc-crete)
                           :auth-key-path remote-ssh-key-path)

    (when (and (not tmp-run) (:image-url spec))
      (let [image-url (:image-url spec)
            config-remote-path (format "%s/config" image-dir)
            backup-config (format "/tmp/%s-lxc-config" container-hostname)]
        (actions/exec-checked-script
         "Backup config file"
         ("cp" ~config-remote-path ~backup-config)
         ("rm -rf" ~image-dir)
         ("mkdir -p" ~image-dir)
         ("rdiff-backup --preserve-numerical-ids --restore-as-of now" ~image-url ~image-dir)
         ("cp" ~backup-config ~config-remote-path))))

    ;; spin up container
    (boot-up-container container-hostname)))

(defplan setup-container-admin-user
  []
  (let [hostname (crate/target-name)
        host-config (env/get-environment [:host-config hostname])
        admin-username (get-in host-config [:admin-user :username])
        admin-ssh-public-key-path (get-in host-config [:admin-user :ssh-public-key-path])
        rootpass (:rootpass host-config)]

    ;; rootpass
    (actions/exec-checked-script
     "change root passwd and wipe root authrized_keys"
     (pipe ("echo" ~(format "root:%s" rootpass))
           ("chpasswd"))
     ("rm -f /root/.ssh/authorized_keys"))

    ;; admin user
    (if (= admin-username "root")
      (ssh-key/authorize-key "root" (slurp admin-ssh-public-key-path))
      (admin-user/automated-admin-user admin-username admin-ssh-public-key-path))))
