(ns babyagi.demo.views
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.string :refer [join]]
   [re-frame.core :as rf]
   [reagent.core :as r]
   [babyagi.views :refer [router vertical-menu]]))

(defn panel []
  [:box#panel
   {:top 0
    :left 0
    :width "50%"
    :height "20%"
    :style {:border {:fg :magenta}}
    :border {:type :line}
    :label " Panel "}
   [:box
    {:top 1
     :left 1
     :right 1}
    [vertical-menu {:options [{:id "task-list-show" :label "Show Task-List"
                               :action #(rf/dispatch [:babyagi.application/prompt!
                                                      [:default
                                                       "WHO?:"
                                                       print]])}
                              {:id "let-the-baby-play" :label "Continue to Play" :action #()}
                              {:id "baby-stop" :label "Pause the Baby" :action #()}]
                    :bg :magenta
                    :fg :black
                    :on-select #(rf/dispatch [:babyagi.application/call-panel-option %])}]]])

(defn objective []
  (fn []
    (let [objective (rf/subscribe [:babyagi.application/objective])]
      [:box#goal
       {:top 0
        :right 0
        :width "50%"
        :height "20%"
        :style {:border {:fg :magenta}}
        :border {:type :line}
        :label " Objective "}
       [:box
        {:top 1
         :left 1
         :right 1}
        [:box
         {:top 1
          :left 1
          :right 1
          :align :left
          :content @objective}]]])))

(defn stats []
  [:box#stats {:bottom 0
               :left 0
               :width "50%"
               :height "80%"
               :style {:border {:fg :magenta}}
               :border {:type :line}
               :label " Stats "}
   [:box {:top 1
          :left 1
          :right 1}
    [:box "STATS......."]]])

(defn task-list []
  (fn []
    (let [task-list (rf/subscribe [:babyagi.application/task-list])]
      [:box#task-list {:bottom 0
                       :right 0
                       :width "50%"
                       :height "80%"
                       :style {:border {:fg :magenta}}
                       :border {:type :line}
                       :label " Task-List "}
       [:box {:top 1
              :left 1
              :right 1}
        [:box
         (for [{:keys [id name]} @task-list]
           ^{:key id} [:text name])]]])))

(defn home
  "Display welcome message and general usage info to user.
  Returns hiccup :box element."
  [_]
  [:box#app {:width  "100%"
             :height "100%"}
   [panel]
   [objective]
   [stats]
   [task-list]])

(defn about
  "Display link to the template project and share features.
  Returns hiccup :box vector."
  [_]
  [:box#about
   {:top 0
    :right 0
    :width "70%"
    :height "50%"
    :style {:border {:fg :blue}}
    :border {:type :line}
    :label " About "}
   [:box#content
    {:top 1
     :left 1
     :right 1
     :bottom 1}
    [:text {:content "Demo ClojureScript Terminal-User-Interface (TUI) app generated from the leiningen cljs-tui template."}]
    [:box {:top 3
           :align :center
           :style {:fg :green}
           :content "https://github.com/eccentric-j/cljs-tui-template"}]
    [:text {:top 5
            :align :center
            :content  (join "\n  - "
                            ["Features:\n"
                             "Use ClojureScript and functional programming\n    to deliver rich CLIs quickly"
                             "Manage your state and side-effects with re-frame"
                             "Compose simple view functions into a rich UI\n    with Reagent React views"
                             "Use web technologies you are already familiar with"
                             "Faster start up time with node"
                             "Supports shadow, figwheel-main, or lein-figwheel"])}]]])

(defn resources
  "Share links to libraries this project is built with.
  Returns hiccup :box vector."
  [_]
  [:box#about
   {:top 0
    :right 0
    :width "70%"
    :height "50%"
    :style {:border {:fg :red}}
    :border {:type :line}
    :label " Resources "}
   [:box#content
    {:top 1
     :left 1
     :right 1
     :bottom 1}
    [:text (join "\n  - "
                 ["Learn more about the technology behind this powerful ClojureScript template:\n"
                  "https://clojurescript.org/"
                  "https://github.com/chjj/blessed"
                  "https://github.com/Yomguithereal/react-blessed"
                  "https://reagent-project.github.io/"
                  "https://shadow-cljs.org/"
                  "https://figwheel.org/"
                  "https://github.com/bhauman/lein-figwheel"])]]])

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
                    ;;:credits credits
                    }
            :view view}]
   child])
