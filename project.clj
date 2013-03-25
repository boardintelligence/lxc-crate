(defproject boardintelligence/lxc-crate "0.1.0-SNAPSHOT"
  :description "Pallet crate for working with LXC servers and containers"
  :url "https://github.com/boardintelligence/lxc-crate"
  :license {:name "MIT"
            :url "http://boardintelligence.mit-license.org"}

  :dependencies [[org.clojure/clojure "1.4.0"]
                 [com.palletops/pallet "0.8.0-beta.5"]
                 [ch.qos.logback/logback-classic "1.0.7"]
                 [boardintelligence/pallet-nodelist-helpers "0.1.0-SNAPSHOT"]]

  :dev-dependencies [[com.palletops/pallet "0.8.0-beta.5" :type "test-jar"]
                     [com.palletops/pallet-lein "0.6.0-beta.7"]]

  :profiles {:dev
             {:dependencies [[com.palletops/pallet "0.8.0-beta.5" :classifier "tests"]]
              :plugins [[com.palletops/pallet-lein "0.6.0-beta7"]]}}

  :local-repo-classpath true)
