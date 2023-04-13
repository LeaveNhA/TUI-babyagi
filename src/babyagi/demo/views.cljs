(ns babyagi.demo.views
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.string :refer [join]]
   [babyagi.keys :refer [with-keys]]
   [re-frame.core :as rf]
   [reagent.core :as r]
   [react-blessed-contrib :as rbc]
   [babyagi.views :refer [router vertical-menu]]))

(defn panel []
  (let [can-play? (rf/subscribe [:babyagi.application/can-play?])]
    [:box#panel
     {:style {:border {:fg :magenta}}
      :border {:type :line}
      :label " Panel "}
     [vertical-menu {:options [{:id "set-objective-for-baby"
                                :clickable? true
                                :label "Set Objective"
                                :action #(rf/dispatch [:babyagi.application/prompt!
                                                       [:default
                                                        "New Objective:"
                                                        (fn [objective]
                                                          (rf/dispatch [:babyagi.application/set-new-objective objective]))]])}
                               {:id "let-the-baby-play"
                                :label "Start to Play!"
                                :clickable? @can-play?
                                :action #(when @can-play?
                                           (rf/dispatch [:babyagi.application/play! true]))}
                               {:id "baby-stop"
                                :label "Stop the Baby!"
                                :clickable? true
                                :action #(rf/dispatch [:babyagi.application/stop])}]
                     :bg :magenta
                     :fg :black
                     :disabled-bg :gray
                     :disabled-fg :white
                     :on-select identity}]]))

(defn objective []
  (fn []
    (let [objective (rf/subscribe [:babyagi.application/objective])]
      [:box#goal
       {:top 0
        :right 0
        :width "50%"
        :style {:border {:fg :magenta}}
        :border {:type :line}
        :label " Objective "}
       [:box
        {:top 1
         :left 1
         :right 1
         :align :left
         :content @objective}]])))

(defn completed-task-list []
  (fn []
    (let [completed-task-list (rf/subscribe [:babyagi.application/completed-task-list])]
      [:box#stats {:style {:border {:fg :magenta}}
                   :border {:type :line}
                   :label " Completed "}
       [:box {:top 1
              :left 1
              :right 1
              :bottom 1}
        (for [[id {:keys [description result]}] (map-indexed vector @completed-task-list)]
          [:text {:key id :top id}
           (str "[" (if result "✓" "X") "]"
                " "
                description)])]])))

(defn limit-string [s limit]
  (if (< (count s) limit)
    s
    (str (subs s 0 limit) "...")))

(defn task-list []
  (let [box-ref-r (r/atom nil)]
    (fn []
      (let [[width height] (if (and @box-ref-r
                                    (and (-> @box-ref-r
                                             ._getWidth)
                                         (-> @box-ref-r
                                             ._getHeight)))
                             (-> @box-ref-r
                                 (#(identity [(-> @box-ref-r
                                                  ._getWidth)
                                              (-> @box-ref-r
                                                  ._getHeight)])))
                             [nil nil])
            max-task-list-item (or (- height 2) 1)
            max-character-in-one-line (or (- width 2 4) 1)
            task-list (rf/subscribe [:babyagi.application/task-list max-task-list-item])]
        [:box#task-list {:ref #(reset! box-ref-r %)
                         :style {:border {:fg :magenta}}
                         :border {:type :line}
                         :label (str " Task-List ")} ;
         (for [[idx {:keys [id description result]}] (map-indexed vector @task-list)]
           [:box {:key (or id (random-uuid)) :top idx}
            (str
             (if id "[" "{") (if result "✓" "X") (if id "]" "}")
                 " "
                 (limit-string (clojure.string/replace description #"\n" "")
                               max-character-in-one-line))])]))))

(defn log-type->log-text-color [log-type]
  (get {:information  "cyan"
        :warning      "yellow"
        :error        "red"}
       log-type "white"))

(defn logs-and-stats []
  (fn []
    (let [stats (rf/subscribe [:babyagi.application/stats])
          logs  (rf/subscribe [:babyagi.application/logs])]
      [:box#goal
       {:top 0
        :right 0
        :left 0
        :style {:border {:fg :magenta}}
        :border {:type :line}
        :label " Logs & Stats "}
       [:> rbc/Grid {:rows 2 :cols 1}
        [:box {:row 0 :col 0 :align :left
               :content @stats}]
        [:> rbc/Log {:row 1 :col 0}
         (for [[idx {:keys [log-type log-text]}] (map-indexed vector @logs)]
           [:box {:key idx
                  :top idx
                  :style {:fg (log-type->log-text-color log-type)}
                  :content log-text}])]]])))

(defn home
  "Display welcome message and general usage info to user.
  Returns hiccup :box element."
  [_]
  (r/with-let [stats&logs-status (r/atom true)
               screen (rf/subscribe [:screen])]
    (with-keys @screen {["\""] #(swap! stats&logs-status not)}
      (if @stats&logs-status
        [:> rbc/Grid {:width "100%" :height "100%"
                      :rows 4 :cols 2}
         [:box {:row 0 :col 0 :row-span 1 :col-span 2 :label "Babyagi | /w proper language"}
          [panel]
          [objective]]
         [:box {:row 1 :col 0 :row-span 1 :col-span 2}
          [task-list]]
         [:box {:row 2 :col 0 :row-span 2 :col-span 2}
          [logs-and-stats]]]
        [:> rbc/Grid {:width "100%" :height "100%"
                      :rows 4 :cols 2}
         [:box {:row 0 :col 0 :row-span 1 :col-span 2 :label "Babyagi | /w proper language"}
          [panel]
          [objective]]
         [:box {:row 1 :col 0 :row-span 3 :col-span 2}
          [task-list]]]))))

(defn credits
  "Give respect and credit to the Denis for inspiring for this project.
  Returns hiccup :box vector."
  [_]
  [:box#about
   {:top 0
    :right 0
    :width "70%"
    :height "50%"
    :style {:border {:fg :yellow}}
    :border {:type :line}
    :label " Credits "}
   [:box#content
    {:top 1
     :left 1
     :right 1
     :bottom 1}
    [:box
     {:top 0
      :align :center
      :content "https://github.com/denisidoro/floki"}]
    [:box
     {:top 2
      :content (join "\n  - "
                     ["This project was deeply inspired by Floki, a ClojureScript TUI created by Denis Isidoro."])}]
    [:box
     {:top 5
      :align :center
      :content "https://git.io/fhhOf"}]
    [:box
     {:top 7
      :content "Special thanks to Camilo Polymeris whose gist inspired Floki and this template."}]
    [:text
     {:top 10}
     (join "\n  - "
           ["Templated created by Eccentric J and is open sourced on github."])]
    [:box
     {:top 12
      :left 0
      :align :left
      :content "- https://github.com/eccentric-j/cljs-tui-template\n- https://eccentric-j.com/"}]]])

(defn loader
  "Shows a mock-loader progress bar for dramatic effect.
  - Uses with-let to create a progress atom
  - Uses a js interval to update it every 15 ms until progress is 100.
  - Starts the timer on each mount.
  - Navigates to home page when completed.
  Returns hiccup :box vector."
  [_]
  (r/with-let [progress (r/atom 0)
               interval (js/setInterval #(swap! progress inc) 15)]
    (when (>= @progress 100)
      (js/clearInterval interval)
      (rf/dispatch [:update {:router/view :home}]))
    [:box#loader
     {:top 0
      :width "100%"}
     [:box
      {:top 1
       :width "100%"
       :align :center
       :content "Loading Demo"}]
     [:box
      {:top 2
       :width "100%"
       :align :center
       :style {:fg :gray}
       :content "Slow reveal for dramatic effect..."}]
     [:progressbar
      {:orientation :horizontal
       :style {:bar {:bg :magenta}
               :border {:fg :cyan}
               :padding 1}
       :border {:type :line}
       :filled @progress
       :left 0
       :right 0
       :width "100%"
       :height 3
       :top 4
       :label " progress "}]]))

(defn demo
  "Main demo UI wrapper.

  Takes a hash-map and a hiccup child vector:

  hash-map:
  :view keyword - Current view keyword that maps to one of the views below.

  child:
  Typically something like a hiccup [:box ...] vector

  Returns hiccup :box vector."
  [{:keys [view]} child]
  [:box#base {:width  "100%"
              :height "100%"}
   [router {:views {:loader loader
                    :home home
                    }
            :view view}]
   #_child])
