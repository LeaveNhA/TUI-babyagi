(ns babyagi.views
  "General views and helpers"
  (:require
   [reagent.core :as r]
   [babyagi.core :refer [screen]]
   [babyagi.keys :refer [with-keys]]))

(defn router
  "Takes a map of props:
  :views map     - Map of values (usually keywords) to hiccup view functions
  :view  keyword - Current view to display. Should be the key of :views map

  Returns the hiccup vector returned by the selected view-fn.

  Example:
  (router {:views {:home home-fn
                   :about about-fn}
           :view :home})
  "
  [{:keys [views view] :as props}]
  [(get views view) props])

(defn- find-index
  "Takes a target value and a map of options.
  Returns index of target value if found in map of options or nil if target
  was not found."
  [target options]
  (some->> options
           (keys)
           (map-indexed vector)
           (filter (fn [[idx v]] (= v target)))
           (first)
           (first)))

(defn- next-option
  "Takes the current keyword key of the options map and a map of options.
  Returns the next key of the map or the first key of the map."
  [current options]
  (let [total (count options)
        current-idx (find-index current options)
        next-idx (inc current-idx)]
    (-> options
        (vec)
        (nth (if (< next-idx total) next-idx 0))
        (key))))

(defn- prev-option
  "Takes the current keyword key of the options map and a map of options.
  Returns the previous key of options map or the last key of the options map."
  [current options]
  (let [total (count options)
        current-idx (find-index current options)
        prev-idx (dec current-idx)]
    (-> options
        (vec)
        (nth (if (< prev-idx 0) (dec total) prev-idx))
        (key))))

(defn vertical-menu
  "Display an interactive vertical-menu component.

  Takes a hash-map of props:
  :bg        keyword|str - Background color of highlighted item.
  :box       hash-map    - Map of props to merge into menu box properties.
  :default   keyword     - Selected options map keyword key
  :fg        keyword|str - Text color of highlighted item.
  :on-select function    - Function to call when item is selected
  :options   hash-map    - Map of keyword keys to item labels

  Returns a reagent hiccup view element.

  Example:
  (vertical-menu
   {:bg :cyan
    :box {:top 3}
    :default :a
    :fg :white
    :on-select #(println \"selected: \" %)
    :options {:a \"Item A\"
              :b \"Item B\"
              :c \"Item C\"}})"
  [{:keys [bg fg
           disabled-fg
           disabled-bg
           box default on-select options]}]
  (r/with-let [selected (r/atom 0)]
    (with-keys @screen {["j" "down"] #(swap! selected (comp (fn [i] (mod i (count options))) inc))
                        ["k" "up"] #(swap! selected (comp (fn [i] (mod i (count options))) dec))
                        ["l" "enter"] #((:action (get options @selected)))}
      (let [current @selected]
        [:box#menu
         (merge
          {:top 1
           :left 1
           :right 1
           :bottom 1}
          box)
         (for [[idx option] (map-indexed vector options)]
           (let [{:keys [id label action clickable?]} option
                 is-selected? (= current idx)]
             [:box {:key id
                    :top idx
                    :style {:bg (if clickable?
                                  (when is-selected? (or bg :green))
                                  (or disabled-bg :gray))
                            :fg (if clickable?
                                  (when is-selected? (or fg :white))
                                  (or disabled-fg :black))}
                    :height 1
                    :content label}]))]))))
