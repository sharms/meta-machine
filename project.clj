(defproject meta-machine "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/tools.cli "0.3.3"]
                 [tentacles "0.3.0"]
                 [com.taoensso/timbre "4.2.0"]
                 [environ "1.0.1"]]
  :plugins [[cider/cider-nrepl "0.8.1"]]
  :main meta-machine.core)
