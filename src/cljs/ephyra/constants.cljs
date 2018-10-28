(ns ephyra.constants)

(def tabs-v [[:all "All"] [:running "Running"] [:failed "Failed"] [:lock-failed "Locked"] [:lost "Lost"]])
(def tabs-map (into {} tabs-v))
(def tabs-keys (map first tabs-v))
(def refresh-interval 5000)  ; ms
(def max-notifications 8)
(def notification-duration 8000)  ; ms; synchronised with CSS
(def rerun-forced-delay 500)
(def default-plan-limit 50)
(def default-job-history-limit 10)
(def plans-in-range-limit 3)
