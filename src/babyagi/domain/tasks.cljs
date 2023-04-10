(ns babyagi.domain.tasks)

(defn db->task-list [db]
  (let [in-time (-> db
                    :babyagi.application/data
                    :in-time)
        task-order (:task-order in-time)
        tasks (:tasks in-time)
        task-list (map tasks task-order)]
    task-list))

(defn db->completed-task-list [db]
  (let [in-time (-> db
                    :babyagi.application/data
                    :in-time)
        tasks (:tasks in-time)
        task-list (filter :result tasks)]
    task-list))
