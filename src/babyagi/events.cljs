(ns babyagi.events
  "Event handlers for re-frame dispatch events.
  Used to manage app db updates and side-effects.
  https://github.com/Day8/re-frame/blob/master/docs/EffectfulHandlers.md"
  (:require [re-frame.core :as rf]
            [openai :as oa]
            [blessed :as bls]))

(rf/reg-event-db
 :init
 (fn [db [_ opts terminal-size screen]]
   {:opts opts
    :screen screen
    :router/view :home
    :terminal/size terminal-size
    :babyagi.application/prompt-styles {:default {:border "line",
                                                  :height "shrink",
                                                  :width "half",
                                                  :top "center",
                                                  :left "center",
                                                  :label " {blue-fg}Your Input{/blue-fg} ",
                                                  :tags true,
                                                  :keys true,
                                                  :vi true}}
    :babyagi.application/data {:openai
                               {:api-key "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",
                                :client nil ;; will populate after initialization!
                                :embedding {:model "text-embedding-ada-002"
                                            :stats {:request 0
                                                    :response 0}}
                                :gpt {:model "gpt-3.5-turbo"
                                      :stats {:request 0
                                              :response 0}}}
                               :pinecone
                               {:api-key "PINECONE_API_KEY",
                                :environment "PINECONE_ENVIRONMENT",
                                :table-name "YOUR_TABLE_NAME",
                                :dimension 1536,
                                :metric "cosine",
                                :pod-type "p1"
                                :stats {:request 0
                                        :response 0}},
                               ;; TODO: [Seçkin] if it's long, make a summary and use it!
                               :objective "Solve world hunger!"
                               :in-time {:time 0 :times [{:task-list [{:id 0, :name "Develop a task list."}]}]}
                               :functions
                               {:add-task {:name "add_task", :args ["task"]},
                                :get-ada-embedding {:name "get-ada-embedding", :args ["text"]},
                                :openai-call! {:name "openai-call", :args ["prompt", "model", "temperature", "max-tokens"]},
                                :task-creation-agent {:name "task-creation-agent", :args ["objective", "result", "task-description", "task_list"]},
                                :prioritization-agent {:name "prioritization-agent", :args ["this-task-id"]},
                                :execution-agent {:name "execution-agent", :args ["objective", "task"]},
                                :context-agent {:name "context-agent", :args ["query", "n"]}}}}))

(rf/reg-event-fx
 :babyagi.application/call-task-creation-agent!
 (fn [{:keys []}]))

(rf/reg-event-db
 :update
 (fn [db [_ data]]
   (merge db data)))

(rf/reg-event-db
 :set
 (fn [db [_ data]]
   data))

(rf/reg-event-fx
 :babyagi.application/initialize-openai-client
 (fn [{:keys [db]}]
   (let [openai-api-key (-> db
                            :babyagi.application/data
                            :openai
                            :api-key)
         openai-client (-> {:apiKey openai-api-key} clj->js
                           (#(new oa/Configuration %))
                           (#(new oa/OpenAIApi %)))]
     {:db (assoc-in db [:babyagi.application/data
                        :openai
                        :client]
                    openai-client)})))

(defonce d (atom nil))

(rf/reg-fx
 :babyagi.application/call-openai-fx!
 (fn [[resolve-fn error-fn prompt model temperature max-tokens]]
   (let [db @(rf/subscribe [:db])
         openai-client (-> db
                           :babyagi.application/data
                           :openai
                           :client)
         request-promise (.createCompletion
                          openai-client
                          (-> {:engine (or model default-model)
                               :prompt prompt
                               :temperature (or temperature
                                                0)
                               :max_tokens (or max-tokens
                                               150)
                               :top_p 1.0
                               :frequency_penalty 0
                               :presence_penalty 0}
                              clj->js))]
     (->
      request-promise
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
         prompt-map (or (when ((complement keyword?)
                               prompt-data-or-keyword)
                          promt-data-or-keyword)
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
                                       promt-callback-fn-or-keyword)
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
