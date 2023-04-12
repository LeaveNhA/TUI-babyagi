(ns babyagi.domain.tasks)

(defn db->task-list [max-item db]
  (let [in-time (-> db
                    :babyagi.application/data
                    :in-time)
        task-order (if (= max-item :inf)
                     (:task-order in-time)
                     (take max-item (:task-order in-time)))
        clean-task-order (filter identity task-order)
        tasks (:tasks in-time)
        task-list (map tasks clean-task-order)]
    task-list))

(defn db->completed-task-list [db]
  (let [in-time (-> db
                    :babyagi.application/data
                    :in-time)
        tasks (:tasks in-time)
        task-list (filter :result tasks)]
    task-list))
