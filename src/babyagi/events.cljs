(ns babyagi.events
  (:require [re-frame.core :as rf]
            [openai :as oa]
            ["@pinecone-database/pinecone" :as pc]
            [cljs.pprint :as pp]
            [clojure.edn]
            [clojure.string]
            [blessed :as bls]
            [babyagi.domain.tasks :as tasks]))

(rf/reg-event-db
 :init
 (fn [db [_ opts terminal-size screen]]
   (let [first-task-identity (random-uuid)]
     {:babyagi.application/data {:in-time {:objective "Solve world hunger!"
                                           :task-order [first-task-identity]
                                           :tasks {first-task-identity {:id first-task-identity
                                                                        :description "Develop a task list"}}}
                                 :openai
                                 {:api-key ""
                                  :client-status :uninitialized
                                  :client nil ;; will populate after initialization!
                                  :embedding {:model "text-embedding-ada-002"
                                              :stats {:request 0
                                                      :response 0}}
                                  :gpt {:model "text-davinci-003"
                                        :stats {:request 0
                                                :response 0}}}
                                 :pinecone
                                 {:api-key "4545dff6-4096-44ab-8cea-e9d10546d081"
                                  :environment "asia-southeast1-gcp"
                                  :client-status :uninitialized
                                  :client nil ;; will populate after initialization!
                                  :index nil  ;; will populate after initialization!
                                  :table-name "lunara-table",
                                  :dimension 1536,
                                  :metric "cosine",
                                  :pod-type "p1"
                                  :stats {:request 0
                                          :response 0}}
                                 :should-play? false
                                 :logs []}
      :babyagi.application/prompt-styles {:default {:border "line",
                                                    :height "shrink",
                                                    :width "half",
                                                    :top "center",
                                                    :left "center",
                                                    :label " {blue-fg}Your Input{/blue-fg} ",
                                                    :tags true,
                                                    :keys true,
                                                    :vi true}}
      :opts opts
      :screen screen
      :router/view :home
      :terminal/size terminal-size})))

(defonce d (atom {}))
(comment
  (-> (rf/subscribe [:db])
      deref
      :babyagi.application/data
      :in-time
      :tasks))

(rf/reg-fx
 :babyagi.application/upsert-pinecone-index!
 (fn [[index
       result-id
       embeddings
       task-data]]
   (let [db @(rf/subscribe [:db])
         {:keys [index]} (-> db
                             :babyagi.application/data
                             :pinecone)]
     [index
      result-id
      embeddings
      task-data])))

(rf/reg-fx
 :babyagi.application/query-pinecone-index!
 (fn [[query-vector top-k include-metadata?]]
   (let [db @(rf/subscribe [:db])
         query-vector []
         {:keys [index
                 table-name
                 dimension
                 metric
                 pod-tpye]} (-> db
                                :babyagi.application/data
                                :pinecone)
         top-k 5 ;; why?
         query-promise false #_(.query index
                                       (clj->js
                                        {:queryRequest
                                         {:vector query-vector
                                          :includeMetadata true
                                          :topK top-k}}))]
     (println (clj->js
               [index table-name query-vector])))))

(rf/reg-event-db
 :babyagi.application/change-pinecone-client-status
 (fn [db [_ new-status]]
   (assoc-in db [:babyagi.application/data
                 :pinecone
                 :client-status]
             new-status)))

(rf/reg-event-fx
 :babyagi.application/create-pinecone-index!
 (fn [{:keys [db]}]
   (let [db @(rf/subscribe [:db])
         {:keys [client
                 table-name]} (-> db
                                  :babyagi.application/data
                                  :pinecone)
         pinecone-index (.Index client table-name)
         new-db (-> db
                    (assoc-in [:babyagi.application/data
                               :pinecone
                               :index]
                              pinecone-index)
                    (assoc-in [:babyagi.application/data
                               :pinecone
                               :client-status]
                              :ready-to-use))]
     (println "index created:" pinecone-index)
     (if pinecone-index
       {:db new-db}
       {:db db}))))

(rf/reg-fx
 :babyagi.application/create-pinecone-table-fx!
 (fn [[pinecone-client
       table-name
       dimension
       metric
       pod-type]]
   (let [create-table-request (.createIndex pinecone-client
                                            (clj->js
                                             {:createRequest
                                              {:name table-name
                                               :dimension dimension
                                               :metric metric
                                               :pod_type pod-type}}))]
     (-> create-table-request
         (.then #(rf/dispatch [:babyagi.application/create-pinecone-index!]))
         (.catch js/console.error)))))

(rf/reg-event-fx
 :babyagi.application/create-pinecone-table!
 (fn [{:keys [db]} _]
   (let [{:keys [client
                 client-status
                 table-name
                 dimension
                 metric
                 pod-tpye]} (-> db
                                :babyagi.application/data
                                :pinecone)]
     (if client-status
       {:db db
        :babyagi.application/create-pinecone-table-fx! [client
                                                        table-name
                                                        dimension
                                                        metric
                                                        pod-tpye]}
       {:db db}))))

(rf/reg-fx
 :babyagi.application/check-pinecone-table-fx!
 (fn [[pinecone-init-promise
       pinecone-client
       table-name
       dimension
       metric
       pod-type]]
   (when pinecone-init-promise
     (.then pinecone-init-promise
            (fn []
              (-> (.listIndexes pinecone-client)
                  (.then #(js->clj % :keywordize-keys true))
                  (.then (comp
                          boolean
                          (fn [s]
                            (get s table-name))
                          (partial into #{})))
                  (.then (fn [table-available?]
                           (if (not table-available?)
                             (rf/dispatch [:babyagi.application/create-pinecone-table!])
                             (rf/dispatch [:babyagi.application/create-pinecone-index!]))))
                  (.catch js/console.error)))))))

(rf/reg-event-fx
 :babyagi.application/initialize-pinecone-client!
 (fn [{:keys [db]}]
   (let [pinecone-api-key (-> db
                              :babyagi.application/data
                              :pinecone
                              :api-key)
         pinecone-environment (-> db
                                  :babyagi.application/data
                                  :pinecone
                                  :environment)
         pinecone-client      (new pc/PineconeClient)
         pinecone-init!       (.init pinecone-client
                                     (clj->js {:apiKey pinecone-api-key
                                               :environment pinecone-environment}))]
     (let [pinecone-init-promise pinecone-init!
           pinecone-client pinecone-client
           {:keys [table-name
                   dimension
                   metric
                   pod-tpye]} (-> db
                                  :babyagi.application/data
                                  :pinecone)]
       {:db (assoc-in db [:babyagi.application/data
                          :pinecone
                          :client]
                      pinecone-client)
        :babyagi.application/check-pinecone-table-fx!
        [pinecone-init-promise
         pinecone-client
         table-name
         dimension
         metric
         pod-type]}))))

(rf/reg-fx
 :babyagi.application/get-embedding-from-ada!
 (fn [openai-client
      succ-callack-fn
      fail-callback-fn
      text]
   (let [db @(rf/subscribe [:db])
         text "and"
         succ-callback-fn (comp
                           ;; TODO: use that data in context-agent!
                           (comp :embedding
                                 #(get % 0)
                                 :data
                                 :data)
                           #(js->clj % :keywordize-keys true))
         fail-callback-fn #(swap! d assoc :fail (js->clj % :keywordize-keys true))
         openai-client (-> db
                           :babyagi.application/data
                           :openai
                           :client)
         model (-> db
                   :babyagi.application/data
                   :openai
                   :embedding
                   :model)
         input (-> text (clojure.string/replace
                         #"\n"
                         ""))
         embedding-promise (.createEmbedding openai-client
                                             (clj->js
                                              {:model model
                                               :input input}))]
     (-> embedding-promise
         (.then succ-callback-fn)
         (.catch fail-callback-fn))
     [text input openai-client model embedding-promise])))

(rf/reg-event-fx
 :babyagi.application/update-task
 (fn [{:keys [db]} [_ task-id task-update-map]]
   {:db (update-in db [:babyagi.application/data
                       :in-time
                       :tasks
                       task-id]
                   merge task-update-map)
    :fx [[:dispatch [:babyagi.application/call-task-creation-agent!]]]}))

(rf/reg-event-fx
 :babyagi.application/call-execution-agent!
 (fn [{:keys [db]} _]
   (let [;db @(rf/subscribe [:db])
         openai-client (-> db
                           :babyagi.application/data
                           :openai
                           :client)
         last-incomplete-task (-> db
                                  tasks/db->task-list
                                  ((partial filter #(not (:result %))))
                                  first)
         objective (-> db
                       :babyagi.application/data
                       :in-time
                       :objective)
         last-completed-tasks (-> db
                                  tasks/db->task-list
                                  ((partial filter :result))
                                  ((partial interpose "\n"))
                                  ((partial reduce str)))
         prompt (str "You are an AI who performs one task based on the following objective: "
                     objective
                     ".\nTake into account these previously completed tasks: "
                     (if (empty? last-completed-tasks)
                       "[none]"
                       last-completed-tasks)
                     "\nYour task: "
                     (:description last-incomplete-task)
                     "\nResponse:")
         model (-> db
                   :babyagi.application/data
                   :openai
                   :gpt
                   :model)
         temperature 0.7
         max-tokens 2000]
     {:babyagi.application/call-openai-fx! [openai-client
                                            (comp
                                             #(rf/dispatch [:babyagi.application/update-task
                                                            (:id last-incomplete-task)
                                                            %])
                                             #(identity {:result %})
                                             #(get-in % [:data :choices 0 :text]))
                                            js/console.error
                                            prompt
                                            model
                                            temperature
                                            max-tokens]
      :db db})))

(rf/reg-event-fx
 :babyagi.application/call-task-creation-agent!
 (fn [{:keys [db]} _]
   (let [db @(rf/subscribe [:db])
         objective (-> db
                       :babyagi.application/data
                       :in-time
                       :objective)
         last-completed-task (-> db
                                 :babyagi.application/data
                                 :in-time
                                 :task-list
                                 ((partial filter :result))
                                 last)
         incomplete-tasks (-> db
                              :babyagi.application/data
                              :in-time
                              :task-list
                              ((partial filter #(not (:result %)))))
         last-completed-task-result (:result last-completed-task)
         last-completed-task-description (:description last-completed-task)
         prompt (str "You are an task creation AI that uses the result of an execution agent to create new tasks with the following objective: "
                     objective
                     ", The last completed task has the result: "
                     (if last-completed-task
                       last-completed-task-result
                       "[none]")
                     ". This result was based on this task description: "
                     (if last-completed-task
                       last-completed-task-description
                       "[none]")
                     ". These are incomplete tasks: "
                     (-> incomplete-tasks
                         ((partial map :description))
                         ((partial interpose ", "))
                         ((partial apply str)))
                     ". Based on the result, create new tasks to be completed by the AI system that do not overlap with incomplete tasks. Response in EDN format with the tasks as an array based on structure of: {:description}, do not comment.")
         model (-> db
                   :babyagi.application/data
                   :openai
                   :gpt
                   :model)
         temperature 0.7
         max-tokens 2000
         openai-client (-> db
                           :babyagi.application/data
                           :openai
                           :client)]
     {:babyagi.application/call-openai-fx! [openai-client
                                            (comp
                                             #(rf/dispatch [:babyagi.application/add-new-tasks %])
                                             clojure.edn/read-string
                                             #(get-in % [:data :choices 0 :text]))
                                            js/console.error
                                            prompt
                                            model
                                            temperature
                                            max-tokens]
      :db db})))

(comment
  (rf/dispatch [:babyagi.application/call-task-creation-agent!]))

(rf/reg-event-fx
 :babyagi.application/add-new-tasks
 (fn [{:keys [db]} [_ new-tasks]]
   (let [tasks (-> db
                   :babyagi.application/data
                   :in-time
                   :tasks)
         task-order (-> db
                        :babyagi.application/data
                        :in-time
                        :task-order)
         new-tasks-identified (map (fn [task]
                                     (let [id (random-uuid)]
                                       {id (assoc task :id id)}))
                                   new-tasks)
         new-tasks (apply merge
                          tasks new-tasks-identified)
         new-task-order (reduce conj
                                task-order
                                (flatten (map keys new-tasks-identified)))]
     {:db (-> db
              (assoc-in [:babyagi.application/data
                         :in-time
                         :task-order]
                        new-task-order)
              (assoc-in [:babyagi.application/data
                         :in-time
                         :tasks]
                        new-tasks))
      :fx [[:dispatch [:babyagi.application/call-prioritization-agent!]]]})))

(comment
  (rf/dispatch [:babyagi.application/add-new-tasks [{:description "Develop task list"}]]))

(rf/reg-event-fx
 :babyagi.application/call-prioritization-agent!
 (fn [{:keys [db]} _]
   (let [;;db @(rf/subscribe [:db])
         objective (-> db
                       :babyagi.application/data
                       :in-time
                       :objective)
         task-list (-> db
                       :babyagi.application/data
                       :in-time
                       :tasks
                       ((partial filter #(not (:result %)))))
         prompt (str "You are an task prioritization AI tasked with cleaning the formatting of and reprioritizing the following tasks: "
                     task-list
                     ". Consider the ultimate objective of your team: "
                     objective
                     ". Do not remove any tasks. Return the result as ordered vector of IDs in EDN format, like:\n"
                     "[#uuid \"2d94bd2c-66b4-46b0-8742-7cf3e461ffc7\" #uuid \"b93c629a-6b6f-4846-988e-9c03ed94a3d4\"]")
         model (-> db
                   :babyagi.application/data
                   :openai
                   :gpt
                   :model)
         temperature 0.7
         max-tokens 2000
         openai-client (-> db
                           :babyagi.application/data
                           :openai
                           :client)]
     (swap! d assoc :prio-req prompt)
     {:babyagi.application/call-openai-fx! [openai-client
                                            (comp
                                             #(rf/dispatch [:babyagi.application/update-task-order %])
                                             clojure.edn/read-string
                                             #(get-in % [:data :choices 0 :text]))
                                            js/console.error
                                            prompt
                                            model
                                            temperature
                                            max-tokens]
      :db db})))

(rf/reg-event-fx
 :babyagi.application/play!
 (fn [{:keys [db]} [_ baby-should-play?]]
   (let [baby-should-play? (or baby-should-play?
                               (-> db
                                   :babyagi.application/data
                                   :should-play?))]
     (if baby-should-play?
       {:db db
        :fx [[:dispatch [:babyagi.application/call-execution-agent!]]]}
       {:db db}))))

(rf/reg-event-db
 :babyagi.application/stop
 (fn [db]
   (assoc-in db [:babyagi.application/data
                 :should-play?]
             false)))

(rf/reg-event-fx
 :babyagi.application/update-task-order
 (fn [{:keys [db]} [_ new-task-order]]
   {:db (assoc-in db [:babyagi.application/data
                      :in-time
                      :task-order]
                  new-task-order)
    :fx [[:dispatch [:babyagi.application/play!]]]}))

(rf/reg-event-db
 :update
 (fn [db [_ data]]
   (merge db data)))

(rf/reg-event-db
 :set
 (fn [db [_ data]]
   data))

(rf/reg-event-db
 :babyagi.application/change-openai-client-status
 (fn [db [_ new-status]]
   (assoc-in db [:babyagi.application/data
                 :openai
                 :client-status]
             new-status)))

(rf/reg-event-fx
 :babyagi.application/initialize-openai-client!
 (fn [{:keys [db]}]
   (let [openai-api-key (-> db
                            :babyagi.application/data
                            :openai
                            :api-key)
         openai-client (-> {:apiKey openai-api-key} clj->js
                           (#(new oa/Configuration %))
                           (#(new oa/OpenAIApi %)))]
     {:db (-> db
              (assoc-in [:babyagi.application/data
                         :openai
                         :client-status]
                        :ready-to-operate)
              (assoc-in [:babyagi.application/data
                         :openai
                         :client]
                        openai-client))})))

(rf/reg-fx
 :babyagi.application/call-openai-fx!
 (fn [[openai-client resolve-fn error-fn prompt model temperature max-tokens]]
   #_(pp/pprint "@ :babyagi.application/call-openai-fx! "
                (clj->js [openai-client resolve-fn error-fn prompt model temperature max-tokens]))
   (let [request-promise (.createCompletion
                          openai-client
                          (clj->js
                           {:model (or model default-model)
                            :prompt prompt
                            :temperature (or temperature
                                             0)
                            :max_tokens (or max-tokens
                                            150)
                            :top_p 1.0
                            :frequency_penalty 0
                            :presence_penalty 0}))]
     #_(pp/pprint "@ :babyagi.application/call-openai-fx! request-promise: " request-promise)
     (->
      request-promise
      (.then #(resolve-fn (js->clj % :keywordize-keys true)))
      (.catch #(error-fn %))))))

(rf/reg-event-fx
 :babyagi.application/exit!
 (fn [_ _]
   #(.exit js/process 0)))

(rf/reg-event-fx
 :babyagi.application/call-panel-option
 ;; no-op, yet!
 #(-> % :db))

(rf/reg-fx
 :babyagi.application/prompt-fx!
 (fn [[db [prompt-data-or-keyword prompt-text prompt-callback-fn-or-keyword]]]
   (let [screen (-> db :screen deref)
         prompt-map (or (when ((complement keyword?)
                               prompt-data-or-keyword)
                          prompt-data-or-keyword)
                        (-> db
                            :babyagi.application/prompt-styles
                            prompt-data-or-keyword)
                        (-> db
                            :babyagi.application/prompt-styles
                            :default))
         prompt-text (or prompt-text "Enter a value:")
         prompt-callback-guard-fn (fn [f error value]
                                    (when value
                                      (f value)))
         prompt-callback-fn (or (when ((complement keyword?)
                                       prompt-callback-fn-or-keyword)
                                  prompt-callback-fn-or-keyword)
                                (when (keyword? prompt-callback-fn-or-keyword)
                                  #(re-frame.core/dispatch [prompt-callback-fn-or-keyword %]))
                                #(print "{DEBUG} NO CALLBACK DEFINED FOR:" prompt-text))
         prompt-map (.prompt blessed (clj->js (merge {:parent screen} prompt-map)))]
     (.input prompt-map prompt-text (partial prompt-callback-guard-fn prompt-callback-fn)))))

(rf/reg-event-fx
 :babyagi.application/prompt!
 (fn [{:keys [db]} [_ prompt-data]]
   {:babyagi.application/prompt-fx! [db prompt-data]
    :db db}))
