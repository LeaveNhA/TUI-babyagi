(ns babyagi.events
  (:require [re-frame.core :as rf]
            [openai :as oa]
            ["@pinecone-database/pinecone" :as pc]
            [cljs.pprint :as pp]
            [clojure.edn]
            [clojure.set :refer [rename-keys]]
            [clojure.string]
            [cljs-node-io.core :as io :refer [slurp spit]]
            [blessed :as bls]
            [babyagi.domain.tasks :as tasks]))

(defn wrap-identity! [f]
  (fn [v]
    (f v)
    v))

(rf/reg-event-db
 :init
 (fn [db [_ opts terminal-size screen]]
   (let [first-task-identity (random-uuid)]
     {:babyagi.application/data {:in-time {:objective "Make LISP great again!"
                                           :context-data nil ;; will depend on the context
                                           :task-order [first-task-identity]
                                           :tasks {first-task-identity {:id first-task-identity
                                                                        :description "Create task list"}}}
                                 :openai
                                 {:api-key ""
                                  :client-status :uninitialized
                                  :client nil ;; will populate after initialization!
                                  :embedding {:model "text-embedding-ada-002"
                                              :usage {:prompt-tokens 0
                                                      :completion-tokens 0
                                                      :total-tokens 0}}
                                  :gpt {:model "text-davinci-003" ;; "gpt-4"
                                        :usage {:prompt-tokens 0
                                                :completion-tokens 0
                                                :total-tokens 0}}}
                                 :pinecone
                                 {:api-key ""
                                  :environment ""
                                  :client-status :uninitialized ;
                                  :client nil ;; will populate after initialization!
                                  :index nil  ;; will populate after initialization!
                                  :table-name "lunara-table",
                                  :dimension 1536,
                                  :metric "cosine",
                                  :pod-type "p1"
                                  :stats {:request 0
                                          :response 0}}
                                 :should-play? true
                                 :logs [{:log-type :information
                                         :log-text "Program ran."}]}
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

(rf/reg-event-fx
 :babyagi.application/save-result-to-pinecone!
 (fn [{:keys [db]} [_ task-id result]]
   (let [result-id task-id
         task (-> db
                  :babyagi.application/data
                  :in-time
                  :tasks
                  (get task-id))
         task-name (:description task)
         openai-client (-> db
                           :babyagi.application/data
                           :openai
                           :client)
         index (-> db
                   :babyagi.application/data
                   :pinecone
                   :index)
         model (-> db
                   :babyagi.application/data
                   :openai
                   :embedding
                   :model)]
     {:fx [[:dispatch [:babyagi.application/log :information "Preparing to save result to Pinecone."]]
           [:dispatch-later {:ms 300 :dispatch [:babyagi.application/upsert-result-to-pinecone! openai-client model index result-id task-name result]}]]
      :db db})))

(rf/reg-event-fx
 :babyagi.application/upsert-result-to-pinecone!
 (fn [{:keys [db]} [_ openai-client model index result-id task-name result]]
   {:db db
    :babyagi.application/upsert-result-to-pinecone-fx! [openai-client model index result-id task-name result]}))

(rf/reg-fx
 :babyagi.application/upsert-result-to-pinecone-fx!
 (fn [[openai-client model index result-id task-name result]]
   (let [embedding-promise (.createEmbedding openai-client
                                             (clj->js {:model model
                                                       :input (clojure.string/replace (str result) #"\n" "")}))]
     (-> embedding-promise
         (.then #(js->clj % :keywordize-keys true))
         (.then #(get-in % [:data :data 0 :embedding] {:not-found-in-path true
                                                       :response-data %}))
         (.then (wrap-identity!
                 #(rf/dispatch [:babyagi.application/log
                                :information
                                (str "Embedding returned: "
                                     %)])))
         (.then (partial safe-upsert! index result-id result))
         (.then #(rf/dispatch [:babyagi.application/log :information (str "The result got from the promise: "
                                                                          %)]))
         (.catch #(rf/dispatch [:babyagi.application/log :error (str "Error on saving into to Pinecone: "
                                                                     %)]))))))

(defn safe-upsert!
  [index result-id result embedding-vector]
  (if (fn? index.upsert)
    (let [upsert-data (clj->js
                       {:upsertRequest
                        {:namespace "babyagi"
                         :vectors
                         [{:id (str result-id)
                           :values embedding-vector
                           :metadata (clj->js (update result :id str))}]}})
          add-to-stats (rf/dispatch [:babyagi.application/add-to-req|resp :request])]
      (-> (index.upsert upsert-data)
          (.then #(js->clj % :keywordize-keys true))
          (.then #(rf/dispatch [:babyagi.application/add-to-req|resp :response]))
          (.then #(rf/dispatch [:babyagi.application/log :information "Calling Task Creation agent."]))
          (.then #(rf/dispatch [:babyagi.application/call-task-creation-agent!]))
          (.catch (wrap-identity!
                   #(rf/dispatch [:babyagi.application/log
                                  :error
                                  (str "Error on upserting embeddings: " %)])))))
    :no-index!))

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
   (let [{:keys [client
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
                              :ready-to-operate))]
     (if pinecone-index
       {:db new-db
        :fx [[:dispatch [:babyagi.application/log :information "Pinecone initialized."]]]}
       {:db db
        :fx [[:dispatch [:babyagi.application/log :information "Pinecone couldn't initialized."]]]}))))

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
 (fn [{:keys [db]} [_ pinecone-environment' pinecone-api-key']]
   (let [pinecone-api-key (or pinecone-api-key'
                              (-> db
                                  :babyagi.application/data
                                  :pinecone
                                  :api-key))
         pinecone-environment (or pinecone-environment'
                                  (-> db
                                      :babyagi.application/data
                                      :pinecone
                                      :environment))
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

(rf/reg-event-fx
 :babyagi.application/call-context-agent!
 (fn [{:keys [db]} [_ query n]]
   (let [openai-client (-> db
                           :babyagi.application/data
                           :openai
                           :client)
         model (-> db
                   :babyagi.application/data
                   :openai
                   :embedding
                   :model)
         index (-> db
                   :babyagi.application/data
                   :pinecone
                   :index)]
     {:fx [[:dispatch [:babyagi.application/log :information "Context agent called!"]]]
      :babyagi.application/get-embeddings-from-ada!
      [openai-client
       #(rf/dispatch [:babyagi.application/query-pinecone-index! index % n])
       js/console.error
       model
       query]
      :db db})))

(rf/reg-event-fx
 :babyagi.application/query-pinecone-index!
 (fn [{:keys [db]} [_ index query-embedding n]]
   (rf/dispatch [:babyagi.application/log :information (str "[BEFORE] Querying Pinecone Index:"
                                                            index)])
   {:db db
    :fx [[:dispatch [:babyagi.application/log :information (str "Querying Pinecone Index:"
                                                                index)]]]
    :babyagi.application/query-pinecone-index-fx! [index query-embedding n]}))

(rf/reg-fx
 :babyagi.application/query-pinecone-index-fx!
 (fn [[index query-embedding n]]
   (let [query-promise (.query index
                               (clj->js {:queryRequest
                                         {:vector query-embedding
                                          :includeMetadata true
                                          :topK n}}))]
     (-> query-promise
         (.then #(js->clj % :keywordize-keys true))
         (.then (wrap-identity!
                 #(rf/dispatch [:babyagi.application/log :information (str "Pinecone query resulted with: "
                                                                           %)])))
         (.then (fn [results]
                  (let [sorted-results (->> results
                                            :matches
                                            (sort-by :score >)
                                            (map :metadata))]
                    sorted-results)))
         (.then (wrap-identity!
                 #(rf/dispatch [:babyagi.application/log :information (str "Queried Pinecone index:" %)])))
         (.then #(rf/dispatch [:babyagi.application/update-context-agent-results %]))
         (.catch js/console.error)))))

(rf/reg-event-fx
 :babyagi.application/update-context-agent-results
 (fn [{:keys [db]} [_ sorted-results]]
   (rf/dispatch [:babyagi.application/log :information "Updating the context data."])
   {:db (assoc-in db [:babyagi.application/data
                      :in-time
                      :context-data]
                  sorted-results)
    :fx [[:dispatch-later {:ms 300 :dispatch [:babyagi.application/call-execution-agent!]}]
         [:dispatch [:babyagi.application/log :information (str "Context agent: context data is" sorted-results)]]
         [:dispatch [:babyagi.application/log :information "Context agent: context updated!"]]]}))

(rf/reg-fx
 :babyagi.application/get-embeddings-from-ada!
 (fn [[openai-client
       succ-callback-fn
       fail-callback-fn
       model
       query]]
   (rf/dispatch [:babyagi.application/log :information (str "Getting embeddings from Ada (RIP) with query: "
                                                            query)])
   (let [data-transformation-fn (comp :embedding
                                      #(get % 0)
                                      :data
                                      :data)
         input (-> query (clojure.string/replace
                         #"\n"
                         ""))
         embedding-promise (.createEmbedding openai-client
                                             (clj->js
                                              {:model model
                                               :input input}))]
     (-> embedding-promise
         (.then #(js->clj % :keywordize-keys true))
         (.then (wrap-identity!
                 #(rf/dispatch [:babyagi.application/add-to-usage :embedding (-> % :data :usage)])))
         (.then (wrap-identity!
                 #(rf/dispatch [:babyagi.application/log :information (str "Embedding came from Ada: "
                                                                           %) ])))
         (.then data-transformation-fn)
         (.then (wrap-identity!
                 #(rf/dispatch [:babyagi.application/log :information (str "Embedding data:"
                                                                           %) ])))
         (.then succ-callback-fn)
         (.catch fail-callback-fn))
     [text input openai-client model embedding-promise])))

(rf/reg-event-fx
 :babyagi.application/update-task
 (fn [{:keys [db]} [_ task-id task-update-map]]
   (let [old-task-map (get-in db [:babyagi.application/data
                                  :in-time
                                  :tasks
                                  task-id])
         updated-task-map (merge old-task-map
                                 task-update-map)]
     {:db (assoc-in db [:babyagi.application/data
                        :in-time
                        :tasks
                        task-id]
                    updated-task-map)
      :fx [[:dispatch [:babyagi.application/log :information (str "Updating task "
                                                                  task-id
                                                                  " with: "
                                                                  task-update-map
                                                                  " resulted: "
                                                                  updated-task-map)]]
           [:dispatch-later {:ms 300 :dispatch [:babyagi.application/save-result-to-pinecone!
                                                task-id
                                                updated-task-map]}]]})))

(rf/reg-event-fx
 :babyagi.application/call-execution-agent!
 (fn [{:keys [db]} _]
   (let [openai-client (-> db
                           :babyagi.application/data
                           :openai
                           :client)
         last-incomplete-task (->> db
                                   (tasks/db->task-list :inf)
                                   (filter #(not (:result %)))
                                   first)
         objective (-> db
                       :babyagi.application/data
                       :in-time
                       :objective)
         context-data (-> db
                       :babyagi.application/data
                       :in-time
                       :context-data)
         last-completed-tasks nil #_(->> db
                                   (tasks/db->task-list :inf)
                                   (filter :result)
                                   (interpose "\n")
                                   (reduce str))
         prompt (str "You are an AI who performs one task based on the following objective: "
                     objective
                     ".\nTake into account these previously completed tasks in format of EDN: "
                     ;; My prev approach before implementing
                     ;; context agent functionality from the
                     ;; original BabyAGI.
                     (mapv :task context-data)
                     #_(if (empty? last-completed-tasks)
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
     {:babyagi.application/call-openai-fx!
      [openai-client
       (comp
          #(rf/dispatch [:babyagi.application/update-task
                         (:id last-incomplete-task)
                          %])
          #(identity {:result %})
          #(get-in % [:data :choices 0 :text]))
       #(rf/dispatch [:babyagi.application/log :error (str "Execution agent: "
                                                           %)])
       prompt
       model
       temperature
       max-tokens]
      :fx [[:dispatch [:babyagi.application/log :information "Execution agent called!"]]]
      :db db})))

(rf/reg-event-fx
 :babyagi.application/call-task-creation-agent!
 (fn [{:keys [db]} _]
   (let [objective (-> db
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
                         #_((partial apply str)))
                     ". Based on the result, create new tasks to be completed by the AI system that do not overlap with incomplete tasks. Response in EDN format with the tasks as an array based on structure of: [\"task 1\" \"task 2\"], do not comment.")
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
                                            #(rf/dispatch [:babyagi.application/log :error %])
                                            prompt
                                            model
                                            temperature
                                            max-tokens]
      :fx [[:dispatch [:babyagi.application/log :information "Task agent called!"]]]
      :db db})))

(rf/reg-event-fx
 :babyagi.application/add-new-tasks
 (fn [{:keys [db]} [_ new-tasks']]
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
                                       {id {:id id
                                            :description task}}))
                                   new-tasks')
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
      :fx [[:dispatch [:babyagi.application/log :information (str "Task agent: New tasks added:"
                                                                  new-tasks'
                                                                  " -> "
                                                                  new-tasks)]]
           [:dispatch [:babyagi.application/call-prioritization-agent!]]]})))

(rf/reg-event-fx
 :babyagi.application/call-prioritization-agent!
 (fn [{:keys [db]} _]
   (let [objective (-> db
                       :babyagi.application/data
                       :in-time
                       :objective)
         task-list (-> db
                       :babyagi.application/data
                       :in-time
                       :tasks
                       ((partial filter (complement :result)))
                       ((partial map second))
                       ((partial map #(select-keys % [:id :description])))
                       ((partial filter :id)))
         prompt (str "You are an task prioritization AI tasked with cleaning the formatting of and reprioritizing the following tasks: "
                     task-list
                     ". Consider the ultimate objective of your team: "
                     objective
                     ". Do not remove any tasks. Return the result as ordered vector of IDs in EDN format, example:\n"
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
     {:babyagi.application/call-openai-fx! [openai-client
                                            (comp
                                             #(rf/dispatch [:babyagi.application/update-task-order %])
                                             (wrap-identity! #(rf/dispatch [:babyagi.application/log :information (str
                                                                                                                   "Prioritization agent returned: "
                                                                                                                   %)]))
                                             clojure.edn/read-string
                                             #(get-in % [:data :choices 0 :text]))
                                            #(rf/dispatch [:babyagi.application/log
                                                           :error
                                                           (str
                                                            "Prioritization agent: failed with:"
                                                            %
                                                            " /w "
                                                            [prompt
                                                             model
                                                             temperature
                                                             max-tokens])])
                                            prompt
                                            model
                                            temperature
                                            max-tokens]
      :fx [[:dispatch [:babyagi.application/log :information "Prioritization agent called."]]]
      :db db})))

(rf/reg-event-fx
 :babyagi.application/play!
 (fn [{:keys [db]} [_ baby-should-play?]]
   (let [baby-should-play? (or baby-should-play?
                               (-> db
                                   :babyagi.application/data
                                   :should-play?))
         objective (-> db
                   :babyagi.application/data
                   :in-time
                   :objective)
         n 5]
     (if baby-should-play?
       {:db db
        :fx [[:dispatch [:babyagi.application/log :information "Started to a cycle!"]]
             [:dispatch [:babyagi.application/call-context-agent! objective n]]]}
       {:db db}))))

(rf/reg-event-fx
 :babyagi.application/stop
 (fn [{:keys [db]}]
   {:db (-> db
            (assoc-in [:babyagi.application/data
                       :should-play?]
                      false))
    :fx [[:dispatch [:babyagi.application/log :information "Stopped auto-cycle!"]]]}))

(rf/reg-event-fx
 :babyagi.application/update-task-order
 (fn [{:keys [db]} [_ new-task-order]]
   (let [auto-cycle? (-> db
                         :babyagi.application/data
                         :should-play?)]
     {:db (assoc-in db [:babyagi.application/data
                        :in-time
                        :task-order]
                    new-task-order)
      :fx [[:dispatch [:babyagi.application/log :information "Prioritization agent: updated task order."]]
           [:dispatch [:babyagi.application/log :information
                       (str "Finished a cycle!"
                            (when auto-cycle? "Running for another one."))]]
           [:dispatch [:babyagi.application/play!]]]})))

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
 (fn [{:keys [db]} [_ openai-api-key']]
   (let [openai-api-key (or openai-api-key'
                            (-> db
                                :babyagi.application/data
                                :openai
                                :api-key))
         openai-client (-> {:apiKey openai-api-key
                            :organization "org-cxnjPgvT4TN97Gy0MpA8shUH"} clj->js
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
                        openai-client))
      :fx [[:dispatch [:babyagi.application/log :information "OpenAI initialized."]]]})))

(defn log-to-file [[file-name log-data]]
  (spit file-name (str log-data "\n")
        :append true))

(rf/reg-fx
 :babyagi.application/log-to-file
 log-to-file)

(rf/reg-event-fx
 :babyagi.application/log
 (fn [{:keys [db]} [_ log-type log-text]]
   (let [log-data {:log-type log-type
                   :log-text log-text}
         log-file-name "babyagi.log"]
     {:db (update-in db [:babyagi.application/data
                         :logs]
                     conj
                     log-data)
      :babyagi.application/log-to-file [log-file-name
                                        (str log-data)]})))

(rf/reg-event-fx
 :babyagi.application/add-to-req|resp
 (fn [{:keys [db]} [_ type-of-interaction]]
   {:db (let [old-usage-data (-> db
                                 :babyagi.application/data
                                 :pinecone
                                 :stats)
              new-usage-data (condp = type-of-interaction
                               :request {:request 1}
                               :response {:response 1}
                               {})
              summed-usage-data (merge-with +
                                            old-usage-data
                                            new-usage-data)
              new-db (assoc-in db [:babyagi.application/data
                                   :pinecone
                                   :stats]
                               summed-usage-data)]
          new-db)
    :fx [[:dispatch [:babyagi.application/log :information (str
                                                            "Usage added: "
                                                            model)]]]}))

(rf/reg-event-fx
 :babyagi.application/add-to-usage
 (fn [{:keys [db]} [_ model usage-data]]
   {:db (if (map? usage-data)
          (let [old-usage-data (-> db
                                   :babyagi.application/data
                                   :openai
                                   model
                                   :usage)
                new-usage-data (rename-keys usage-data
                                            {:prompt_tokens :prompt-tokens
                                             :completion_tokens :completion-tokens
                                             :total_tokens :total-tokens})
                summed-usage-data (merge-with +
                                              old-usage-data
                                              new-usage-data)
                new-db (assoc-in db [:babyagi.application/data
                                     :openai
                                     model
                                     :usage]
                                 summed-usage-data)]
            new-db)
          db)
    :fx [[:dispatch [:babyagi.application/log :information (str
                                                            "Usage added: "
                                                            model)]]]}))

(rf/reg-fx
 :babyagi.application/call-openai-fx!
 (fn [[openai-client resolve-fn error-fn prompt model temperature max-tokens]]
   (let [request-promise (.createCompletion
                          openai-client
                          (clj->js
                           {:model model
                            :prompt prompt
                            :temperature (or temperature
                                             0)
                            :max_tokens (or max-tokens
                                            150)
                            :top_p 1.0
                            :frequency_penalty 0
                            :presence_penalty 0}))]
     (-> request-promise
         (.then #(js->clj % :keywordize-keys true))
         (.then (wrap-identity!
                 #(rf/dispatch [:babyagi.application/log
                                :information
                                (str "Response from OpenAI API:"
                                     %)])))
         (.then (wrap-identity!
                 #(rf/dispatch [:babyagi.application/add-to-usage :gpt (-> % :data :usage)])))
         (.then resolve-fn)
         (.catch error-fn)))))

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
         prompt-map (or (when ((complement keyword?) prompt-data-or-keyword)
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

(rf/reg-event-db
 :babyagi.application/set-new-objective
 (fn [db [_ new-objective]]
   (assoc-in db [:babyagi.application/data
                  :in-time
                  :objective]
             new-objective)))
