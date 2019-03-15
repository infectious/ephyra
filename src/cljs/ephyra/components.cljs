(ns ephyra.components
  (:require [clojure.string :as string]
            [cljs.spec.alpha :as s]
            [cljs.pprint :refer [pprint]]
            [cljs-time.core :as t]
            [cljs-time.format :as f]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [goog.events :as events]
            [goog.string :as gstring]
            [ephyra.constants :refer [tabs-v tabs-map plans-in-range-limit]]
            [ephyra.conversions :refer [to-qs-date]]
            [ephyra.routing :refer [url-for-with-qs set-token!]]
            [ephyra.validation :as v]
            [cljs-pikaday.reagent :as pikaday]
            [scrolly-wrappy.core :refer [scrolly-wrappy]])
  (:require-macros [reagent.ratom :refer [reaction]]))


(def half (partial * 0.5))

(defn translate [& x-y-translations]
  (string/join "," (for [[x y] x-y-translations]
                (str "translate(" x ", " y ")"))))

(def nbsp (gstring/unescapeEntities "&nbsp;"))
(def empty-val "Long dash" "–")

(def job-report-lines 6.7)
(def job-report-line-height 12)
(def job-report-line-labels-column-margin 7)
(def job-report-line-values-column-margin 50)
(def job-rect-height (* job-report-line-height job-report-lines))
(def job-rect-width 200)
(def job-rect-mid (half job-rect-width))
(def job-rect-values-column-offset 60)
(def graph-margin {:horizontal 15 :vertical 10})
(def node-separation 35)
(def rank-separation 20)
(def edge-separation 20)

;;; SVG doesn't support line breaking so we implement it our own (very wrong) way:
;;; 30 is too much on Rui's laptop.
(def chars-per-line 26)

(defn split-job-name
  "Split the name after the first underscore left to the `chars-per-line` index."
  [job-name]
  (let [pivot (if (< chars-per-line (count job-name))
                     (inc (string/last-index-of job-name "_" chars-per-line))
                     chars-per-line)]
  [(subs job-name 0 pivot)
   (subs job-name pivot)]))


(defn job-box-title [[first-line second-line]]
  (let [[second-line first-line-offset second-line-offset]
        (if (not-empty second-line)
          [second-line job-report-line-height job-report-line-height]
          [" "  ; The line must be not empty so the next line positions relatively.
           (* 1.5 job-report-line-height)
           (half job-report-line-height)])]
  [:tspan {:text-anchor "middle" :font-weight "bold"}
   [:tspan {:x job-rect-mid :dy first-line-offset} first-line]
   [:tspan {:x job-rect-mid :dy second-line-offset} second-line]]))

(defn job-box-label [label]
  [:tspan {:x job-report-line-labels-column-margin :dy job-report-line-height} (str label ": ")])

(defn job-box-value
  ([label] (job-box-value label "black"))
  ([label color]
   [:tspan {:x (+ job-report-line-labels-column-margin
                  job-rect-values-column-offset)
            :fill color} label]))

(def status-color
  {"failed" "red"
   "partial_failure" "red"
   "lock_failed" "red"
   "lock_waiting" "#960"
   "success" "green"
   "not run" "grey"
   "running" "#33b"})

(defn human-status [s]
  (string/replace s "_" " "))

(defn colored-status [status]
  (job-box-value (human-status status) (status-color status)))

(defn nilable-job-box-value [v]
  (if (nil? v)
    (job-box-value empty-val "grey")
    (job-box-value v)))

(defn job-box
  "
  Draws a box containing information about the current and previous invocation of the job.

  Our interpretation of the x, y coordinates:

        x
     ___↓___
    |       |
  y→|   +   |
    |_______|
  "
  [job x y & {:as props}]
  (let [hovering? (r/atom false)]
    (fn [job x y & {:as props}]
      (let [search-pattern @(rf/subscribe [:filtering-name])
            matched? (and (not-empty search-pattern)
                          (string/includes? (:name job) search-pattern))]
        [:g (merge
              {:transform (translate [x y] (map (comp - half) [job-rect-width job-rect-height]))
               :on-mouse-enter #(reset! hovering? true)
               :on-mouse-leave #(reset! hovering? false)}
              props)
         [:rect {:width job-rect-width :height job-rect-height :rx 10
                 :stroke "#444"
                 :fill (if matched? "#fffed9" "#fff")
                 :stroke-width (if @hovering? "3" "2")}]
         [:text {:dy job-report-line-height}
          [job-box-title (split-job-name (:name job))]
          [job-box-label "Status"] (colored-status (:last-status job))
          [job-box-label "Started"] (nilable-job-box-value (:started job))
          [job-box-label "Stopped"] (nilable-job-box-value (:stopped job))
          [job-box-label "Retries"] (job-box-value (:retries job))]]))))

(defn path
  "Draws a path between the nodes."
  [is-highlighted-atom vertices]
  (let [[color width] (if @is-highlighted-atom ["#f22" "2px"] ["#222" "1px"])]
    (let [[x-start y-start] (first vertices)
          [x-end y-end] (last vertices)
          mid-vertices (rest (butlast vertices))
          x-mid (/ (apply + (map first mid-vertices)) (count mid-vertices))
          y-mid (/ (apply + (map second mid-vertices)) (count mid-vertices))]
      [:path {:d (str "M" x-start "," y-start
                      "Q" x-mid   "," y-mid
                      " " x-end   "," y-end)
              :fill "none"
              :stroke color
              :stroke-width width}])))

(defn graph-layout
  "Calculates a directed, potentially multi-root graph layout using Dagre.

  The input graph is a map of :nodes (a sequence of maps containing :name and some additional data)
  and :links (a sequence of 2-tuples of node indices).

  Returns a map with layout information, where:
  :root-x is the x coordinate of the root element;
  :nodes if a seq of maps with :x and :y coords and :data, containing whatever was passed within
  the :nodes argument;
  :links is a seq of 3-tuples containing node links paths' vertices;
  :width and :height include the margins.

  All node coordinates point to their centres."
  [{:keys [nodes links]}]
  (let [graph
        (doto (js/dagre.graphlib.Graph.)
          (.setGraph #js {})
          (.setDefaultEdgeLabel (fn [] #js {}))
          (.setGraph #js {:nodesep node-separation
                          :edgesep edge-separation
                          :ranksep rank-separation
                          :marginx (:horizontal graph-margin)
                          :marginy (:vertical graph-margin)}))]

    (doseq [{node-name :name :as node} nodes]
      (.setNode graph node-name #js{"width" (+ job-rect-width)
                                    "height" (+ job-rect-height)
                                    :data node}))

    (doseq [link-tuple links]
      (let [[node-from node-to] (for [index link-tuple] (:name (nth nodes index)))]
        (.setEdge graph node-from node-to)))

    (js/dagre.layout graph)

    (let [g (.graph graph)
          computed-nodes (map #(.node graph %) (.nodes graph))
          link-paths (for [edge (.edges graph)
                           :let [edge-path (aget (.edge graph edge) "points")]]
                       {:from (aget edge "v")
                        :to (aget edge "w")
                        :path (for [point edge-path] [point.x point.y])})]
      {:root-x (let [[root-name] (.sources graph)
                     root (.node graph root-name)]
                 root.x)
       :width g.width
       :height g.height
       :nodes (for [computed-node computed-nodes]
                {:x computed-node.x :y computed-node.y :data computed-node.data})
       :links link-paths})))

(defn dependency-graph
  "SVG graph with nodes' paths highlighted on hover."
  [graph handle-job-selected]
  (let [hovered-node-name (r/atom nil)]
    (fn [graph handle-job-selected]
      (let [{:keys [width height nodes links root-x]} (graph-layout graph)
            is-dragged? (r/atom false)]
        [:div {:on-mouse-leave #(reset! hovered-node-name nil)}
         [scrolly-wrappy {:initial-centre-fn (constantly root-x)
                          :on-drag-start #(reset! is-dragged? true)
                          :on-drag-end #(reset! is-dragged? false)}
          [:svg {:style {:margin "0 auto" :display "block"}
                 :width width :height height
                 :viewBox (str "0 0 " width " " height)
                 :font-size "11px"}

           ;; Boxes and links:
           (doall (for [{path-vertices :path from :from to :to} links]
                    ^{:key [from to]}
                    ;; We pass a reaction to the path so only it gets re-rendered if the reaction
                    ;; changes. We don't dereference hovered-node-name here because redering may be
                    ;; expensive.
                    [path
                     (reaction (#{from to} @hovered-node-name))
                     path-vertices]))

           (for [{job :data :keys [x y]} nodes]
             ^{:key (str (:name job) "-box")}
             [job-box job x y
              :on-mouse-over #(reset! hovered-node-name (:name job))
              :on-mouse-up (fn [e]
                             (when (and (= e.button 0) (not @is-dragged?))
                               (handle-job-selected (:name job))))
              ])]]]))))

(defn job-header [action-column-present?]
  [:thead.table-active
   [:tr
    [:th "Run plan UUID"]
    [:th.version-expand {:title "Expand/collapse"
                         :on-click #(rf/dispatch [:toggle-version-column-width])}
     "Version (" (if @(rf/subscribe [:version-column-expanded]) "-" "+") ")"
     ]
    [:th "Started"]
    [:th "Stopped"]
    [:th "Status"]
    [:th "Lock failures"]
    (when action-column-present? [:th])]])

(defn state-class [state]
  (case state
    "lock_waiting" "warning"
    "lock_failed" "danger"
    "running" "info"
    "success" "success"
    "failed" "danger"
    "partial_failure" "danger"
    "lost" "secondary"))

(defn single-job-entry [{:keys [run_plan_uuid version started stopped state lock-failures
                                output output-length]}
                         action-column]
  [:tbody {:class (str "table-" (state-class state))}
   [:tr
    [:td [:code (str run_plan_uuid)]]
    [:td {:title version}
     [:code
      (if @(rf/subscribe [:version-column-expanded])
        version
        (str (subs version 0 6) "…"))]]
    [:td started]
    [:td (or stopped empty-val)]
    [:td (human-status state) ]
    [:td (or lock-failures empty-val)]
    action-column]
   (when output
     [:tr.job-output
      [:td {:col-span (if action-column 7 6)}
       [:small.float-right
        [:span "Output length: " output-length " chars"]]
       [:pre.code [:code output]]]])])

(defn job-history-table [history]
  [:table.table.job-table.job-history-table
   [job-header true]
   (doall (for [entry history
                :let [uuid (str (:run_plan_uuid entry))]]
            ^{:key uuid}
            [single-job-entry entry
             [:td [:a.button.btn.btn-sm.btn-outline-primary.float-right
                   {:href (url-for-with-qs :job-invocation
                                           (assoc @(rf/subscribe [:route-params]) :uuid uuid)
                                           @(rf/subscribe [:query-params]))}
                   "→ Detail"]]]))])

(defn input-prepend
  ([label] (input-prepend label {}))
  ([label attrs]
   [:div.input-group-prepend attrs [:span.input-group-text label]]))

(defn limit-input
  "Limit input component that updates the passed atom only when the actual input matches
  the ::v/input spec."
  ([value on-change] (limit-input value on-change ""))
  ([value on-change input-class]
   [:div.input-group
    {:class input-class}
    [input-prepend "Limit"]
    [:input.form-control.limit-input
     {:class (when (and (some? value) (not (s/valid? ::v/limit value))) "is-invalid")
      :type "text"
      :value value
      :on-change (fn [e] (on-change e.target.value))}]]))

(defn labeled-checkbox [checked-state on-change label]
  [:div.form-check.ml-2
   [:input.form-check-input
     {:type "checkbox"
      :checked checked-state
      :on-change on-change}]
   [:label.form-check-label " " label]])

(defn rerun-button [requesting? rerun-fn]
  [:button.btn.btn-sm.btn-outline-primary.rerun-job
   {:type "button"
    :class (when requesting? "disabled")
    :on-click rerun-fn}
   (if requesting? "Wait..." "Rerun")])

(defn pretty-format [clj-data]
  [:pre [:code (string/trim (with-out-str (pprint clj-data)))]])

(defn error-box [{:keys [status status-text params response]}]
  [:div.alert.alert-danger {:role "alert"}
   [:p [:strong "Error: " status " - " status-text]]
   (if (zero? status)
     "Check your network."
     [:span
      [:h5 "Show that your devops team:"]
      [:h6 "Params:"] [pretty-format params]
      [:h6 "Response:"] [:pre [:code (:error response)]]])])

(defn loading-result-error
  "Handles loading/result/error status. The :response and :error keys are not mutually
  exclusive."
  [{:keys [result error]} ok-component]
  [:div
   (when (= nil result error)
     [:div
      [:div.progress
       [:div.progress-bar.progress-bar-striped.progress-bar-animated.bg-info
        {:style {:width "100%"}}]]])
   (when (some? error)
     [error-box error])
   (when (some? result)
     [ok-component result])])

(defn job-history
  "Job graph or selected job history view."
  []
  [:div
   [:div.row
    [:div.col-md-6
     [:h5 "Job history – " @(rf/subscribe [:selected-job-name])]]
    [:div.col-md-6

     [:a.button.btn.btn-sm.btn-outline-primary.float-right
      {:href (url-for-with-qs :main-report
                              (dissoc @(rf/subscribe [:route-params]) :job-name)
                              @(rf/subscribe [:query-params]))}
      "Back to graph"]

     [:form.form-inline
      [limit-input
       @(rf/subscribe [:selected-job-filtering-limit])
       #(rf/dispatch [:update-job-filtering-limit %])
       "input-group-sm"]
      [labeled-checkbox
       @(rf/subscribe [:selected-job-filtering-failed-only])
       #(rf/dispatch [:update-job-filtering-failed-only])
       "Failures only"]]]]

   [:div.row
    [:div.col-md-12
     [loading-result-error
      @(rf/subscribe [:job-history-response])
      job-history-table]]]])

(defn job-invocation-table
  [{uuid :run_plan_uuid  job-name :name :as entry}]
  [:div.row
   [:div.col-md-12
    [:table.table.job-table
     [job-header true]
     [single-job-entry
      (update entry :output #(or % ""))  ; Make it display the output even if there's none.
      [:td [rerun-button
            (@(rf/subscribe [:job-rerun-request-pending]) [job-name uuid])
            #(rf/dispatch [:request-job-rerun job-name uuid])]]]
     ]]])

(defn job-invocation
  "Single job invocation view with full output."
  []
  [:div
   [:div.row
    [:div.col-md-10
     [:h5 "Job invocation – " @(rf/subscribe [:selected-job-name]) ": "
      [:code (str @(rf/subscribe [:selected-job-invocation-uuid]))]]]
    [:div.col-md-2

     [:a.button.btn.btn-sm.btn-outline-primary.float-right
      {:href (url-for-with-qs :job-history
                              (dissoc @(rf/subscribe [:route-params]) :uuid)
                              @(rf/subscribe [:query-params]))}
      "Back to history"]]]

   [loading-result-error @(rf/subscribe [:job-invocation-response]) job-invocation-table]])

(defn breakage-on-underscores
  "Inserts newline hints (<wbr> tags) after underscores."
  [text]
  (->> (string/split text #"_")
       (interpose "_")  ; Insert delimiters back.
       (partition-all 2)
       (map #(apply str %))  ; Glue strings to underscores.
       (interpose [:wbr])
       (into [:span])))

(defn plans-in-range [root-job-name previous-results]
  (let [expanded? (@(rf/subscribe [:plan-histories-expanded]) root-job-name)
        shown-results (if expanded? previous-results (take plans-in-range-limit previous-results))
        over-limit? (> (count previous-results) plans-in-range-limit)]
    [:table.table.table-sm
     [:thead.table-active
      [:tr [:th.align-middle
            [:span
             (if expanded?
               "All plans in range"
               (str "Last " (count shown-results)
                    (if (= (count shown-results) 1) " plan" " plans")
                    " of "
                    (count previous-results) " in range"))
             ]]
       [:th.align-middle
        [:span "Job results: "
         (for [[state no] (frequencies (->> previous-results
                                            (mapcat :jobs)
                                            (map :state)))]
           [:span.align-baseline {:key state}
            [:span.badge {:class (str "badge-" (state-class state))}
             (str (human-status state) ": " no)]
            " "])]]
       [:th
        (when over-limit?
          (if expanded?
            [:button.btn.btn-sm.btn-primary.float-right
             {:on-click #(rf/dispatch [:collapse-plans-in-range root-job-name])}
             "Collapse"]
            [:button.btn.btn-sm.btn-primary.float-right
             {:on-click #(rf/dispatch [:expand-plans-in-range root-job-name])}
             "See all"]
            ))]]]
     [:tbody
      (doall (for [[i result] (map-indexed vector shown-results)
                   :let [{:keys [jobs uuid]} result]]
               [:tr {:key uuid}
                [:td {:width "25%"} [:code uuid]]
                [:td {:col-span 2}
                 (doall (for [{:keys [name state]} jobs]
                          [:span {:key name}
                           [:a {:href (url-for-with-qs :job-invocation
                                                       (assoc @(rf/subscribe [:route-params])
                                                              :job-name name :uuid uuid)
                                                       @(rf/subscribe [:query-params]))
                                :title (human-status state)}
                            [:span.badge {:class (str "badge-" (state-class state))} name]]
                           " "]
                          ))]]))
      ]]))

(defn plan-report
  "Job graph or selected job history view."
  [{:keys [root-job-name graph started start-time stop-time interval version previous-results]
    :as plan}]
  [:div.main-item
   [:div.row.plan-header
    [:div.col-md-5
     [:div.row
      [:div.col-md-10
       [:strong [:code [breakage-on-underscores root-job-name]]]
       [:br]
       [:small "ver. " [:code version]]]
      [:div.col-md-2 [rerun-button
                      (@(rf/subscribe [:plan-rerun-request-pending]) root-job-name)
                      #(rf/dispatch [:request-plan-rerun root-job-name])]]]]
    [:div.col-md-2
     "Schedule"
     [:br]
     (or start-time empty-val) (when stop-time (str " - " stop-time))]
    [:div.col-md-2
     "Interval"
     [:br]
     (if interval (f/unparse-duration (t/seconds interval)) empty-val)]
    [:div.col-md-3
     "Plan Started (UTC)"
     [:br]
     (string/replace started " UTC" "")]]
   [dependency-graph graph
    #(set-token! (url-for-with-qs :job-history
                                  (assoc @(rf/subscribe [:route-params]) :job-name %)
                                  @(rf/subscribe [:query-params])))]

   [plans-in-range root-job-name previous-results]])

(defn nav-link [[destination-tab title]]
  (let [jobs-count (destination-tab @(rf/subscribe [:states-tally]) "?")
        pill-class (when (not (zero? jobs-count))
                     (cond
                       (#{:failed :lock-failed} destination-tab) "badge-danger"
                       (#{:running} destination-tab) "badge-info"))]
    [:li.nav-item {:key destination-tab}
     [:a.nav-link {:class (when (= @(rf/subscribe [:tab]) destination-tab) "active")
                   :href (url-for-with-qs :main-report
                                          (assoc @(rf/subscribe [:route-params]) :tab destination-tab)
                                          @(rf/subscribe [:query-params]))}
      title nbsp [:span.badge.badge-pill {:class (or pill-class "badge-dark")}
                  [:span jobs-count]]]]))

(defn datepicker [label date-symbol label-attrs input-attrs on-clear]
  [:div.input-group.datepicker
   [input-prepend label label-attrs]
   [pikaday/date-selector
    {:date-atom (rf/subscribe [date-symbol])
     :pikaday-attrs {:on-select (fn [selected]
                                  (rf/dispatch [:update-filtering {date-symbol selected}])
                                  (rf/dispatch [:apply-filtering]))}
     :input-attrs (merge
                    {:class (string/join " " [(:class input-attrs) "form-control"])
                     :value (if-let [date @(rf/subscribe [date-symbol])]
                              (.toDateString date)
                              "Most recent")
                     :on-change (constantly false)
                     :read-only "readonly"}
                    (dissoc input-attrs :class))}]
   (when on-clear
     [:button.btn.btn-secondary.clear-input
      {:type "button"
       :title "Clear \"To\" Filtering"
       :on-click on-clear}
      "⨯"])])

(defn filtering-toolbar []
  [:div.filtering-toolbar.ml-md-auto
   [:form.form-inline
    {:on-submit (fn [e] (rf/dispatch [:apply-filtering]) (.preventDefault e))}
    [:input {:type "submit" :style {:display "none"}}] ;; To make enter trigger the submit event.
    [:div.form-group.ml-auto
     [:div.input-group
      [input-prepend "Filter"]
      [:input.form-control.filter-input
       {:type "text"
        :placeholder "job_name"
        :value @(rf/subscribe [:filtering-name])
        :on-change #(rf/dispatch [:update-filtering {:name (-> % .-target .-value)}])
        :on-blur #(rf/dispatch [:apply-filtering])
        }]]
     [limit-input
      @(rf/subscribe [:filtering-limit])
      #(rf/dispatch [:update-filtering {:limit %}])]]

    [:div.form-group.ml-auto
     [datepicker "From" :date-from {} {:class "datepicker-from"} nil]
     [datepicker "To" :date-to {:class "input-group-prepend-lean"} {:class "datepicker-to"}
      #(rf/dispatch [:update-filtering {:date-to nil}])]]]])

(defn focus-toggle-button [label focus]
  [:a.btn.btn-outline-secondary
   {:class (when (= @(rf/subscribe [:focus]) focus) "active")
    :href (url-for-with-qs :main-report
                           (assoc @(rf/subscribe [:route-params]) :focus focus)
                           @(rf/subscribe [:query-params]))}
   [:input {:type "radio"}]
   label])

(defn jobs-plans-toggle []
  [:div.jobs-plans-toggle
   [:div.btn-group.btn-group-toggle
    [focus-toggle-button "Plans" :plans]
    [focus-toggle-button "Jobs" :jobs]]])

(defn navbar []
  [:nav.navbar.navbar-expand.navbar-light.bg-light
   [:a.navbar-brand {:href "/"} "Job Dashboard"]
   [:ul.nav.nav-pills
    (doall (map nav-link tabs-v))
    [:li [jobs-plans-toggle]]]
   [filtering-toolbar]])

(defn no-results-msg []
  (let [selected-tab @(rf/subscribe [:tab])]
    [:div.alert.alert-info.clearfix {:role "alert"}

     (when (not= selected-tab :all)
       [:a.button.btn.btn-lg.btn-success.float-right
        {:href (url-for-with-qs :main-report
                                (assoc @(rf/subscribe [:route-params]) :tab :all)
                                @(rf/subscribe [:query-params]))}
        "Go to All"])

     [:strong "No results for the tab '" (tabs-map selected-tab) "'."]
     [:br] "Review filtering."]))

(defn fetching-msg []
  [:div.alert.alert-info.fetching-msg {:role "alert"}
   "Fetching..."])

(defn updates-feed-box [messages]
  [:div.updates-feed {:role "alert"}
   (for [message messages]
     [:div.alert (select-keys message [:key :class]) (:text message)])])

(defn overlay [wrapped]
  [:div.overlay
   [:div.overlay-shade
    {:on-click #(set-token! (url-for-with-qs
                              :main-report
                              (dissoc @(rf/subscribe [:route-params]) :uuid :job-name)
                              @(rf/subscribe [:query-params])))}]
   [:div.overlay-content wrapped]])

(defn plans-report [plans]
  [:div
   (if-let [plans (not-empty plans)]
     (for [plan plans]
       ^{:key (str (:root-job-name plan) "-" (:started plan))}
       [plan-report plan])

     [no-results-msg])])

(defn job-report
  [{:keys [name previous-results] :as job}]
  [:div.main-item
   [:div.row
    [:div.col-10 [:h5 "Job – " name]]
    [:div.col-2
     [:a.button.btn.btn-sm.btn-outline-primary.float-right
      {:href (url-for-with-qs :main-report
                              (assoc @(rf/subscribe [:route-params]) :focus :plans)
                              (assoc @(rf/subscribe [:query-params]) :name name))}
      "Go to plan!"]
     ]]
   [:table.table.job-table
    [job-header true]
    [single-job-entry job
     [:td [:a.button.btn.btn-sm.btn-outline-primary.float-right
           {:href (url-for-with-qs :job-history
                                   (assoc @(rf/subscribe [:route-params]) :job-name name)
                                   @(rf/subscribe [:query-params]))}
           "Full history"]]]]

   [:p.past-items
    [:span.badge.badge-light "All jobs in range:"] " "
    (doall (for [[i result] (map-indexed vector previous-results)
                 :let [{:keys [state uuid]} result]]
             [:span {:key i}
              [:a {:href (url-for-with-qs :job-invocation
                                          (assoc @(rf/subscribe [:route-params])
                                                 :job-name name :uuid uuid)
                                          @(rf/subscribe [:query-params]))
                   :title (str "Plan: " uuid)}
               [:span.badge {:class (str "badge-" (state-class state))} (human-status state)]]
              " "]))]])

(defn jobs-report [jobs]
  [:div
   (if-let [jobs (not-empty jobs)]
     (for [job jobs]
       ^{:key (:name job)}
       [job-report job])

     [no-results-msg])])

(defn page []
  [:div
   [navbar]
   [:div.content
    (case @(rf/subscribe [:focus])
      :plans [loading-result-error
              @(rf/subscribe [:job-plans-items])
              plans-report]
      :jobs [loading-result-error
             @(rf/subscribe [:jobs-report-items])
             jobs-report]
      )]

   (case @(rf/subscribe [:route])
     :job-history [overlay [job-history]]
     :job-invocation [overlay [job-invocation]]
     nil)

   (when @(rf/subscribe [:fetching?])
     [fetching-msg])
   (when-let [updates @(rf/subscribe [:updates-feed])]
     [updates-feed-box updates])])

(defn mount-components []
  (r/render [#'page] (.getElementById js/document "app")))
