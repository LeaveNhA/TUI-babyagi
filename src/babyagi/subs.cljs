(ns babyagi.subs
  "Re-frame app db subscriptions. Essentially maps a keyword describing a
  result to a function that retrieves the current value from the app db."
  (:require [re-frame.core :as rf]))

(rf/reg-sub
  :db
  (fn [db _]
    db))

(rf/reg-sub
  :view
  (fn [db _]
    (:router/view db)))

(rf/reg-sub
  :size
  (fn [db _]
    (:terminal/size db)))

(rf/reg-sub
 :babyagi.application/task-list
 (fn [db _]
   (-> db
       :babyagi.application/data
       ((juxt (comp :time :in-time)
              (comp :times :in-time)))
       ((fn [[time-idx times]]
          (get times time-idx)))
       :task-list)))

(rf/reg-sub
 :babyagi.application/objective
 (fn [db _]
   (-> db
       :babyagi.application/data
       :objective)))
