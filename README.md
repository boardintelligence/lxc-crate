# lxc-crate

A Pallet crate to work with KVM servers and KVM guests.

There is no KVM backend for jclouds/pallet and this is a first attempt
at "manually" supporting KVM. By manually I mean that all operations
are done via the node-list provider and lift (converge is never used).

Hence you define your servers and guests in a config map (the format is
described further down), then perform specific phases on the KVM server(s) to:
* Setup an already existing host as a KVM server
* Create a KVM guest VM on a given KVM server
* Create one big VLAN spanning several KVM servers

After that you can of course perform operations on the KVM guest VMs
directly without going via the KVM server.

For the moment kvm-crate assumes KVM servers are Ubuntu 12.10 hosts. This
restriction can loosened in the future if others provide variations that
works on other distributions and versions.

kvm-crate utilizes the following for setting up VMs and networking:
* python-vm-builder to create VM images from scratch (if not using base images)
* libvirt for managing guests
* openvswitch for networking (and GRE+IPSec for connecting OVS on several KVM servers)

## Configuring a KVM server

First all make sure the following things hold true:

1. Your intended KVM server host exists and runs Ubuntu 12.10
2. The host is included in your hosts config map with a proper config

Step 2 will be described in more detail shortly, let's first see how
the call looks (assuming you are use'ing the kvm-create.api namespace):

    (with-nodelist-config [hosts-config {}]
      (configure-kvm-server "host.to.configure")

*with-nodelist-config* comes from the *pallet-nodelist-helpers* helper project.
The second argument to *with-nodelist-config* is a map that will be passed as the
:environment argument to any subsequent lift operation that takes place under the
covers (and is hence available to any of your own pallet plan functions).

The format of the hosts-config argument that *configure-kvm-server* looks for is this (it's ok to
add additional content):

    {"host.to.configure" {:host-type :kvm-server
                          :group-spec kvm-create.specs/kvm-server-g
                          :ip "probably same as host0-ip4-ip below"
                          :private-ip "probably same as host1-ip4-ip below" ;; ip on the private network
                          :private-hostname "host-int.to.configure"         ;; hostname on the private network
                          :admin-user {:username "root"
                                       :ssh-public-key-path  (utils/resource-path "ssh-keys/kvm-keys/kvm-id_rsa.pub")
                                       :ssh-private-key-path (utils/resource-path "ssh-keys/kvm-keys/kvm-id_rsa")
                                       :passphrase "foobar"}
                          :interfaces-file "ovs/interfaces" ;; template for /etc/network/interfaces
                          :interface-config {:host0-ip4-ip        "public.iface.ip.address"
                                             :host0-ip4-broadcast "public.iface.bcast"
                                             :host0-ip4-netmask   "public.iface.netmask"
                                             :host0-ip4-gateway   "public.iface.gw"
                                             :host0-ip4-net       "public.iface.net"
                                             :host1-ip4-ip        "private.iface.address"
                                             :host1-ip4-broadcast "private.iface.bcast"
                                             :host1-ip4-netmask   "private.iface.netmask"
                                             :host1-ip4-net       "private.iface.net"}
                          :ovs-setup (utils/resource-path "ovs/ovs-setup.sh"}}

(The function *utils/resource-path* is from the namespace pallet.utils and
is handy for referring to paths somewhere onthe local machine classpath)

I've left the configuration of the OpenVSwitch network pretty free form. Hence
the parts in *:interface-config*, *:interfaces-file* and *:ovs-setup* are freeform
and needs to be compatible with each other. You can find an example in the
*kvm-crate/resources/ovs/interfaces-sample* and *kvm-crate/resources/ovs/ovs-setup.sh-sample*.
It is compatible with the config example above. The example assumes one public interface
on eth0 that is added to the OVS, we create 2 interfaces pub0 and priv0. The pub0
inteface gets the same config as eth0 would have had before we made it part of
the OVS setup, and priv0 is an interace we put on the private network we'll
set up for the KVM guest VMs. We can use this interface to do things like serve
DHCP and DNS on the private network.

The *ovs-setup* script is a freeform set of instructions that are supposed
to properly configure OVS taking into account how your /etc/network/interfaces
and later private network for the KVM guest VMs are setup. The example
mentioned above is compatible with the setup descrived in the previous paragraph.

The *configure-kvm-server* function in the *api* namespace can be used
as a convenience method to perform the KVM server setup (as apart to
manually using lift). Note it will make the network changes made on the
KVM server come into effect 2 minutes after it has run (via the at command).
This is because the network changes may cause our connection to drop and
signal an error. To avoid this we delay execution until after we have
disconnected from the host. **NOTE: if you mess up the ovs-setup steps it's
quite possible to lock yourself out of the remote machine so please take
extra special care making sure it will run cleanly since you may not get
a 2nd shot if you don't have console access to the host!**.

You can use the *configure-kvm-server* function in your own functions to
create more complete functions that do more than just the KVM server
configuration. For example this is how we setup newly ordered Hetzner
servers as KVM servers:

    (defn configure-hetzner-kvm-server
      "Setup a KVM server in Hetzner data-center"
      [hostname]
      (helpers/with-nodelist-config [hosts-config/config {}]
        (println (format "Initial setup for Hetzner host %s.." hostname))
        (hetzner-api/hetzner-initial-setup hostname)
        (println (format "Configuring KVM server for %s.." hostname))
        (kvm-api/configure-kvm-server hostname)
        (println (format "Finished configuring KVM server %s. Note: it will perform the network changes in 2min!" hostname))))

(the *hetzner-initial-setup* function performs actions needed to
for a fresh Hetzner machine)

A tip when working with the host config maps is to DRY up your code by
definining parameterized functions that produce maps of certain types
representing certain types of hosts. This way you can also compose
several such functions via *merge*. An example is that you could have
one function producing the config map required by the Hetzner crate,
and another for the kvm crate. Use both of these + merge to create
a config map for a host at Hetzner that is also a KVM server.

## Creating a KVM guest VM

NOT IMPLEMENTED YET

## Connecting the OVS's of several KVM servers

To create one big VLAN where KVM guests on seveal KVM servers can
communicate with each other we use GRE+IPSec to connect the
openvswitches of the KVM servers.

The *connect-kvm-servers* function in the *api* namespace will
connect your KVM servers as specified by the gre connections in
the config. This is again done in a pretty free form way to
give maximum flexibility, at the cost of making it possible for
you to shoot yourself in the foot. The host config map info related
to gre connections take the following form:

    {"host2.to.configure" {:gre-connections
                           [{:bridge "ovsbr1"
                             :iface "gre0"
                             :remote-ip "1.2.3.4"
                             :psk "my secret key"}]}
     "host1.to.configure" {:gre-connections
                           [{:bridge "ovsbr1"
                             :iface "gre0"
                             :remote-ip "4.3.2.1"
                             :psk "my secret key"}]}}

In this example we only have two hosts and there's one GRE connection
setup between the two. If we had more we'd just specify pairs of
matching GRE connections for the appropriate hosts (note the vector
of hashes, just add more hashes to have more connections for a given
host).

The function takes no arugments and can be called like this:

    (kvm-crate.api/connect-kvm-servers)

### Using one KVM server on the private network as DHCP server for guest VMs

In order to be able to assign guest VMs IPs via DHCP we need to assign one
server to act as DHCP server for the private network. We use *dnsmasq* for
this purpose, as well as acting as a forwarding DNS server for the guest VMs.

To do this I've provided the server spec *kvm-crate.specs/kvm-dhcp-server*
that your group spec for the actual KVM server should inherit from.

This spec has a *:configure* phase that does the following:

* Use an upstart job to start dnsmasq when our internal interface is up.
* Update the dnsmasq dhcp config (hosts and options file).

Notes:

* An example dnsmasq options files can be found in
  *resources/ovs/ovs-net-dnsmasq.opts-sample*.
* You can use *(kvm-crate.api/update-dhcp-config dhcp-server-hostname)* to
  dynamically generate the dnsmasq hosts file (and reread the config via
  kill -HUP) including all guest VMs.

The host config map needed to support the spec and the update function is:

    {"dhcp.server.to.configure" {:dhcp-interface "priv1"
                                 :dnsmasq-optsfile (utils/resource-path "ovs/ovs-net-dnsmasq.opts"}}

### Ensuring all hosts, servers and guest, have proper /etc/hosts files

You can use *kvm-crate.api/update-etc-hosts-files* to update the /etc/hosts files
of all hosts with the non-DHCP assigned IPs on the private network.

## License

Copyright © 2013 Board Intelligence

Distributed under the MIT License, see
[http://boardintelligence.mit-license.org](http://boardintelligence.mit-license.org)
for details.
