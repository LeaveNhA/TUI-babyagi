(ns babyagi.subs
  "Re-frame app db subscriptions. Essentially maps a keyword describing a
  result to a function that retrieves the current value from the app db."
  (:require [re-frame.core :as rf]
            [babyagi.domain.tasks :as tasks]))

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
 (partial tasks/db->task-list 4))

(rf/reg-sub
 :babyagi.application/completed-task-list
 tasks/db->completed-task-list)

(rf/reg-sub
 :babyagi.application/logs
 (fn [db]
   (-> db
       :babyagi.application/data
       :logs
       ((partial take-last 4))
       vec)))

(rf/reg-sub
 :babyagi.application/stats
 (fn [db]
   (let [baby (-> db
                  :babyagi.application/data)
         [embedding-stats
          gpt-stats] (map #(-> baby :openai % :usage)
                          [:embedding :gpt])
         pinecone-stats (-> baby :pinecone :stats)]
     (str "OpenAI:\n[GPT            :" gpt-stats
          "]\n[Embedding (Ada):" embedding-stats "]" "\n"
          "Pinecone: " pinecone-stats))))

(rf/reg-sub
 :babyagi.application/can-play?
 (fn [db]
   (let [baby (:babyagi.application/data db)
         [openai pinecone] (-> baby
                               ((juxt
                                 (comp :client-status :openai)
                                 (comp :client-status :pinecone))))]
     (every? (partial = :ready-to-operate)
             [openai pinecone]))))

(comment
  (rf/subscribe [:babyagi.application/can-play?]))

(rf/reg-sub
 :babyagi.application/objective
 (fn [db _]
   (-> db
       :babyagi.application/data
       :in-time
       :objective)))
