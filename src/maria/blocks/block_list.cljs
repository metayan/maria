(ns maria.blocks.block-list
  (:require [re-view.core :as v :refer [defview]]
            [maria.blocks.history :as history]
            [cljsjs.markdown-it]
            [re-db.d :as d]
            [maria.blocks.code]
            [maria.blocks.prose]
            [maria.blocks.blocks :as Block]
            [maria-commands.exec :as exec]
            [maria.commands.blocks]
            [maria.eval :as e]))

(defview block-list
  {:key                     :source-id
   :view/initial-state      (fn [{value :value}] (history/initial-state value))
   :view/did-mount          (fn [this]
                              (history/after-mount (:view/state this))
                              (exec/set-context! {:block-list this})
                              (when (= (str (d/get-in :router/location [:query :eval])) "true")
                                (e/on-load #(exec/exec-command-name :eval/doc))))
   :undo                    (fn [{:keys [view/state]}] (history/undo state))
   :redo                    (fn [{:keys [view/state]}] (history/redo state))
   :clear-history           (fn [{:keys [view/state]}] (history/clear! state))
   :view/will-unmount       #(exec/set-context! {:block-list nil})
   :view/will-receive-props (fn [{value                       :value
                                  source-id                   :source-id
                                  {prev-source-id :source-id} :view/prev-props
                                  state                       :view/state
                                  :as                         this}]
                              (when (not= source-id prev-source-id)
                                ;; clear history when editing a new doc
                                (history/clear! state))
                              (when (not= value (:last-update @state))
                                ;; re-parse blocks when new value doesn't match last emitted value
                                (history/add! state (Block/ensure-blocks (Block/from-source value)))))
   :view/should-update      (fn [{:keys                   [view/state
                                                           on-update]
                                  {prev-history :history} :view/prev-state}]
                              (let [{:keys [history]} @state
                                    blocks-changed? (not= (first history)
                                                          (first prev-history))]
                                (when blocks-changed?
                                  (let [updated-source (Block/emit-list (first history))]
                                    (v/swap-silently! state assoc :last-update updated-source)
                                    (on-update updated-source)))
                                blocks-changed?))
   :get-blocks              #(first (:history @(:view/state %)))
   :splice                  (fn [this & args]
                              (apply history/splice (:view/state this) args))}
  [{:keys [view/state] :as this}]
  (let [{:keys [history]} @state
        blocks (first history)]
    (into [:.w-100.flex-none.pv3]
          (mapv (fn [block]
                  (Block/render block {:blocks        blocks
                                       :block-list    this
                                       :before-change history/before-change})) blocks))))
