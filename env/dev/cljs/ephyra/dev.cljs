(ns ^:figwheel-no-load ephyra.app
  (:require [ephyra.core :as core]
            [ephyra.components :refer [mount-components]]
            [devtools.core :as devtools]
            [figwheel.client :as figwheel :include-macros true]))

(enable-console-print!)

(figwheel/watch-and-reload
  :websocket-url "ws://localhost:3449/figwheel-ws"
  :on-jsload mount-components)

(devtools/install!)

(core/init!)
