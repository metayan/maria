(ns maria.code.node-view
  (:require [applied-science.js-interop :as j]
            [maria.prose.schema :as prose-schema]
            ["prosemirror-state" :refer [TextSelection Selection]]
            ["@codemirror/view" :as view :refer [EditorView]]
            ["prosemirror-history" :as history]
            ["prosemirror-keymap" :as pk]
            ["@codemirror/commands" :as cmd]
            ["react-dom/client" :as react.client]
            ["react" :as react]
            [reagent.core :as reagent]
            [tools.maria.react-roots :as roots]))

(defn use-watch [ref]
  (let [[value set-value!] (react/useState [nil @ref])]
    (react/useEffect
     (fn []
       (add-watch ref set-value! (fn [_ _ old new] (set-value! [old new])))
       #(remove-watch ref set-value!)))
    value))

(defn result-viewer [!result code-dom]
  (let [value (second (use-watch !result))]
    [:div.-mx-4.mb-4.md:flex
     [:div {:class "md:w-1/2 text-base bg-white"
            :style {:color "#c9c9c9"}
            :ref #(when % (.appendChild % code-dom))}]

     [:div
      {:class "md:w-1/2 text-sm bg-slate-300"
       :on-click #(swap! !result (fnil inc 0))}
      "Count!"
      " "
      value]]))

(j/js

  (defn handle-forward-update
    "When the code-editor is focused, forward events from it to ProseMirror."
    [{:as this :keys [code-view prose-view prose-pos updating?]} code-update]
    (let [{prose-state :state} prose-view]
      (when (and (.-hasFocus code-view) (not updating?))
        (let [!offset (volatile! (inc (prose-pos)))
              {from' :from to' :to} (.. code-update -state -selection -main)
              {code-changed? :docChanged
               code-changes :changes} code-update
              {prose-selection :selection
               :keys [tr]} prose-state
              prose-selection' (.create TextSelection
                                        (.-doc prose-state)
                                        (+ @!offset from')
                                        (+ @!offset to'))
              selection-changed? (not (.eq prose-selection prose-selection'))]
          (when (or code-changed? selection-changed?)
            (let [tr (.setSelection tr prose-selection')]
              (.iterChanges code-changes
                            (fn [from-a to-a from-b to-b {:as text :keys [length]}]
                              (let [offset @!offset]
                                (if (pos-int? length)
                                  (.replaceWith tr
                                                (+ offset from-a)
                                                (+ offset to-a)
                                                (.text prose-schema/schema (.toString text)))
                                  (.delete tr
                                           (+ offset from-a)
                                           (+ offset to-a))))
                              ;; adjust offset for changes in length caused by the change,
                              ;; so further steps are in correct position
                              (vswap! !offset + (- (- to-b from-b)
                                                   (- to-a from-a)))))
              (.dispatch prose-view tr)))))))

  (defn- controlled-update [this f]
    (j/!set this :updating? true)
    (f)
    (j/!set this :updating? false))

  (defn handle-set-selection
    "Called when ProseMirror tries to put the selection inside the node."
    [{:as this :keys [code-view]} anchor head]
    (.focus code-view)
    (controlled-update this
      #(.dispatch code-view {:selection {:anchor anchor
                                         :head head}})))

  (defn code-arrow-handler
    "Moves cursor out of code block when navigating out of an edge"
    [{:keys [prose-pos
             code-view
             prose-node
             prose-view]} unit dir]
    (let [{{:keys [doc selection]} :state} code-view
          {:keys [main]} selection
          {:keys [from to]} (if (= unit "line")
                              (.lineAt doc (.-head main))
                              main)]
      (cond (not (.-empty main)) false ;; something is selected
            (and (neg? dir) (pos? from)) false ;; moving backwards but not at beginning
            (and (pos? dir) (< to (.-length doc))) false ;; moving forwards, not at end
            :else
            (let [prose-pos' (+ (prose-pos) (if (neg? dir) 0 (.-nodeSize prose-node)))
                  {:keys [doc tr]} (.-state prose-view)
                  selection (.near Selection (.resolve doc prose-pos') dir)]
              (doto prose-view
                (.dispatch (.. tr
                               (setSelection selection)
                               (scrollIntoView)))
                (.focus))))))

  (defn text-diff [old-text new-text]
    (let [old-end (.-length old-text)
          new-end (.-length new-text)
          start (loop [start 0]
                  (if (and (< start old-end)
                           (== (.charCodeAt old-text start)
                               (.charCodeAt new-text start)))
                    (recur (inc start))
                    start))]
      (loop [old-end old-end
             new-end new-end]
        (if (and (> old-end start)
                 (> new-end start)
                 (== (.charCodeAt old-text (dec old-end))
                     (.charCodeAt new-text (dec new-end))))
          (recur (dec old-end)
                 (dec new-end))
          {:from start
           :to old-end
           :insert (.slice new-text start new-end)}))))

  (defn handle-update [{:as this :keys [code-view]} prose-node]
    (boolean
     (when (= (.-type (j/get this :prose-node)) (.-type prose-node))
       (j/!set this :prose-node prose-node)
       (let [new-text (.-textContent prose-node)
             old-text (.. code-view -state -doc (toString))]
         (when (not= new-text old-text)
           (controlled-update this
             (fn []
               (.dispatch code-view {:changes (text-diff old-text new-text)})))))
       true)))

  (defn select-node [{:keys [code-view]}]
    (.focus code-view))

  (defn code-keymap [{:as this prose-view :prose-view}]
    (let [code-arrow-handler (partial code-arrow-handler this)]
      (.of view/keymap
           [{:key :ArrowUp
             :run #(code-arrow-handler :line -1)}
            {:key :ArrowLeft
             :run #(code-arrow-handler :char -1)}
            {:key :ArrowDown
             :run #(code-arrow-handler :line 1)}
            {:key :ArrowRight
             :run #(code-arrow-handler :char 1)}
            {:key :Ctrl-Enter
             :run (fn [] (if (cmd/exitCode (.-state prose-view) (.-dispatch prose-view))
                           (do (.focus prose-view)
                               true)
                           false))}
            {:key :Ctrl-z :mac :Cmd-z
             :run (fn [] (history/undo (.-state prose-view) (.-dispatch prose-view)))}
            {:key :Shift-Ctrl-z :mac :Shift-Cmd-z
             :run (fn [] (history/redo (.-state prose-view) (.-dispatch prose-view)))}
            {:key :Ctrl-y :mac :Cmd-y
             :run (fn [] (history/redo (.-state prose-view) (.-dispatch prose-view)))}])))

  (defn extensions [this]
    [(code-keymap this)
     (.. EditorView
         -updateListener
         (of (partial handle-forward-update this)))])


  (defn init [this code-view]
    (let [!result (atom nil)
          parent (js/document.createElement "div")
          root (react.client/createRoot parent)
          this (j/extend! this
                 {:!result !result
                  ;; NodeView API
                  :dom parent
                  :forwardUpdate (partial handle-forward-update this)
                  :update (partial handle-update this)
                  :selectNode (partial select-node this)
                  :setSelection (partial handle-set-selection this)
                  :stopEvent (constantly true)
                  :destroy #(do (.destroy code-view)
                                (roots/unmount! root))

                  ;; Internal
                  :updating? false
                  :code-view code-view})]
      ;; TODO - implement eval, putting results into right-panel
      (roots/init! root #(reagent/as-element ^:clj [result-viewer !result (.-dom code-view)]))
      this))

  (defn prose-arrow-handler [dir]
    (fn [state dispatch view]
      (boolean
       (when (and (.. state -selection -empty) (.endOfTextblock view dir))
         (let [$head (.. state -selection -$head)
               {:keys [doc]} state
               pos (if (pos? dir)
                     (.after $head)
                     (.before $head))
               next-pos (.near Selection (.resolve doc pos) dir)]
           (j/log :name (j/get-in next-pos [:$head :parent :type :name]) (= :code_block (j/get-in next-pos [:$head :parent :type :name])))
           (when (= :code_block (j/get-in next-pos [:$head :parent :type :name]))
             (dispatch (.. state -tr (setSelection next-pos)))
             true)))))))