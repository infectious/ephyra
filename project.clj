(defproject ephyra "0.1.0-SNAPSHOT"

  :description "Generic DAG Jobs Dashboard"
  :url "https://github.com/infectious/ephyra"

  :dependencies [[cider/cider-nrepl "0.21.1"]
                 [org.clojars.karo/scrolly-wrappy "0.1.1"]
                 [clj-http "3.9.1"]
                 [clj-statsd "0.4.0"]
                 [clj-time "0.15.1"]
                 [cljs-ajax "0.8.0"]
                 [cljs-pikaday "0.1.4"]
                 [cljsjs/react-dom "16.3.2-0"]
                 [cljsjs/react "16.3.2-0"]
                 [clojure-future-spec "1.9.0"]  ; Backport of core.spec
                 [com.7theta/re-frame-fx "0.2.1"]
                 [com.andrewmcveigh/cljs-time "0.5.2"]
                 [com.google.cloud.sql/postgres-socket-factory "1.0.12"
                  :exclusions [com.google.guava/guava mysql/mysql-connector-java]]
                 [compojure "1.6.1"]
                 [conman "0.6.3"] ; New conman has broken exceptions, keeping [conman "0.8.3"].
                 [cprop "0.1.13"]
                 [funcool/bide "1.6.0"]
                 [io.sentry/sentry-clj "0.7.2"]
                 [io.sentry/sentry-logback "1.7.22"]
                 [io.weft/gregor "1.0.0" :exclusions [log4j/log4j org.slf4j/slf4j-log4j12]]
                 [io.weft/gregor "1.0.0"]
                 [kibu/pushy "0.3.8"]
                 [luminus-immutant "0.2.5"]
                 [luminus-migrations "0.5.2"] ; Not upgraded - "0.6.4" causes problems
                 [luminus-nrepl "0.1.6"]
                 [markdown-clj "1.0.7"]
                 [metosin/ring-http-response "0.9.1"]
                 [mount "0.1.16"]
                 [org.apache.kafka/kafka_2.12 "2.1.1"]
                 [org.clojure/clojure "1.9.0"]
                 [org.clojure/clojurescript "1.10.520" :scope "provided"]
                 [org.clojure/core.async "0.4.490"]
                 [org.clojure/test.check "0.9.0"]  ; Used by clojure.spec/gen.
                 [org.clojure/tools.cli "0.4.1"]
                 [org.clojure/tools.logging "0.4.1"]
                 [org.postgresql/postgresql "42.2.5"]
                 [org.slf4j/log4j-over-slf4j "1.7.26"]  ; Kafka indirectly depends on slf4j.
                 [org.webjars.bower/tether "1.4.4"]
                 [org.webjars/bootstrap "4.3.1"]
                 [org.webjars/font-awesome "5.7.2"]
                 [org.webjars/webjars-locator-jboss-vfs "0.1.0"]
                 [re-frame "0.10.6"]
                 [reagent "0.8.1"]
                 [reagent-utils "0.3.2"]
                 [ring-middleware-format "0.7.4"]
                 [ring-webjars "0.2.0"]
                 [ring/ring-defaults "0.3.2"]
                 [secretary "1.2.3"]
                 [selmer "1.12.9"]
                 [slingshot "0.12.2"]]

  :min-lein-version "2.0.0"

  :jvm-opts ["-server" "-Dconf=.lein-env"]
  :source-paths ["src/clj" "src/cljc"]
  :test-paths ["test/clj"]
  :resource-paths ["resources" "target/cljsbuild"]
  :target-path "target/%s/"
  :main ephyra.core
  :migratus {:store :database :db ~(get (System/getenv) "DATABASE_URL")}

  :plugins [[lein-cprop "1.0.1"]
            [migratus-lein "0.4.3"]
            [lein-cljsbuild "1.1.4"]
            [lein-immutant "2.1.0"]
            [jonase/eastwood "0.2.8"]]
  :clean-targets ^{:protect false}
  [:target-path [:cljsbuild :builds :app :compiler :output-dir] [:cljsbuild :builds :app :compiler :output-to]]
  :figwheel
  {:http-server-root "public"
   :nrepl-port 7002
   :css-dirs ["resources/public/css"]
   :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl cider.nrepl/cider-middleware]}
  :nvd {:suppression-file "security-excuses.xml"}

  :profiles
  {:uberjar {:omit-source true
             :prep-tasks ["compile" ["cljsbuild" "once" "min"]]
             :cljsbuild
             {:builds
              {:min
               {:source-paths ["src/cljc" "src/cljs" "env/prod/cljs"]
                :compiler
                {:output-to  "target/uberjar/cljsbuild/public/js/app.js"
                 ;:output-to "target/cljsbuild/public/js/app.js"
                 :output-dir "target/uberjar/cljsbuild/public/js/out"
                 :externs ["react/externs/react.js"
                           "externs/dagre.js"]
                 :optimizations :advanced
                 :source-map "target/uberjar/cljsbuild/public/js/app.map"
                 :pretty-print false
                 :closure-warnings
                 {:externs-validation :off :non-standard-jsdoc :off}}}}}
             :aot :all
             :uberjar-name "ephyra.jar"
             :source-paths ["env/prod/clj"]
             :resource-paths ["env/prod/resources"  "target/uberjar/cljsbuild"]}

   :dev           [:project/dev :profiles/dev]
   :test          [:project/dev :project/test :profiles/test]

   :project/dev  {:dependencies [[prone "1.6.1"]
                                 [ring/ring-mock "0.3.2"]
                                 [ring/ring-devel "1.7.1"]
                                 [pjstadig/humane-test-output "0.9.0"]
                                 [binaryage/devtools "0.9.10"]
                                 [com.cemerick/piggieback "0.2.2"]
                                 [doo "0.1.11"]
                                 [figwheel-sidecar "0.5.16"]
                                 [day8.re-frame/re-frame-10x "0.3.7"]]
                  :plugins      [[cider/cider-nrepl "0.16.0"]
                                 [com.jakemccrary/lein-test-refresh "0.14.0"]
                                 [lein-doo "0.1.7"]
                                 [lein-figwheel "0.5.16"]
                                 [lein-nvd "0.4.2"]
                                 [org.clojure/clojurescript "1.10.520"]]
                  :cljsbuild
                  {:builds
                   {:app
                    {:source-paths ["src/cljs" "src/cljc" "env/dev/cljs"]
                     :compiler
                     {:main "ephyra.app"
                      :asset-path "/js/out"
                      :output-to "target/cljsbuild/public/js/app.js"
                      :output-dir "target/cljsbuild/public/js/out"
                      :source-map true
                      :closure-defines {"re_frame.trace.trace_enabled_QMARK_" true}
                      :preloads [day8.re-frame-10x.preload]
                      :optimizations :none
                      :pretty-print true}}}}
                  :doo {:build "test"}
                  :source-paths ["env/dev/clj" "test/clj"]
                  :resource-paths ["env/dev/resources"]
                  :repl-options {:init-ns user
                                 :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
                  :injections [(require 'pjstadig.humane-test-output)
                               (pjstadig.humane-test-output/activate!)]}
   :project/test {:resource-paths ["env/test/resources"]
                  :cljsbuild
                  {:builds
                   {:test
                    {:source-paths ["src/cljc" "src/cljs" "test/cljs"]
                     :compiler
                     {:output-to "target/test.js"
                      :main "ephyra.doo-runner"
                      :optimizations :whitespace
                      :pretty-print true}}}}
                  }
   :profiles/dev {}
   :profiles/test {}})
