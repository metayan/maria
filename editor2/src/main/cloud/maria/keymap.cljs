(ns cloud.maria.keymap
  (:require [applied-science.js-interop :as j]
            [cloud.maria.schema :refer [maria-schema]]
            ["prosemirror-commands" :as cmd :refer [baseKeymap]]
            ["prosemirror-keymap" :refer [keymap]]
            ["prosemirror-schema-list" :as cmd-list]))

(def default-keys (keymap baseKeymap))

(def maria-keys
  (j/let [mac? (and (exists? js/navigator)
                    (.test #"Mac|iPhone|iPad|iPod" js/navigator.platform))
          ^js {{:keys [strong em code]} :marks
               {:keys [bullet_list ordered_list blockquote
                       hard_break list_item paragraph
                       code_block heading horizontal_rule]} :nodes} maria-schema
          hard-break-cmd (cmd/chainCommands cmd/exitCode
                                            (fn [^js state dispatch]
                                              (when dispatch
                                                (dispatch (.. state -tr
                                                              (replaceSelectionWith (.create hard_break))
                                                              (cmd/scrollIntoView))))
                                              true))]
         (j/lit∞ (keymap (j/extend! {
                                     :Backspace cmd/undoInputRule
                                     :Alt-ArrowUp cmd/joinUp
                                     :Alt-ArrowDown cmd/joinDown
                                     :Mod-BracketLeft cmd/lift
                                     :Escape cmd/selectParentNode

                                     [:Mod-b
                                      :Mod-B] (cmd/toggleMark strong)
                                     [:Mod-i
                                      :Mod-I] (cmd/toggleMark em)
                                     "Mod-`" (cmd/toggleMark code)
                                     :Shift-Ctrl-8 (cmd/toggleMark bullet_list)
                                     :Shift-Ctrl-9 (cmd/toggleMark ordered_list)

                                     :Ctrl-> (cmd/wrapIn blockquote)

                                     :Enter (cmd-list/splitListItem list_item)
                                     "Mod-[" (cmd-list/liftListItem list_item)
                                     "Mod-]" (cmd-list/sinkListItem list_item)

                                     :Shift-Ctrl-0 (cmd/setBlockType paragraph)
                                     "Shift-Ctrl-\\\\" (cmd/setBlockType code_block)
                                     :Shift-Ctrl-1 (cmd/setBlockType heading {:level 1})
                                     :Shift-Ctrl-2 (cmd/setBlockType heading {:level 2})
                                     :Shift-Ctrl-3 (cmd/setBlockType heading {:level 3})
                                     :Shift-Ctrl-4 (cmd/setBlockType heading {:level 4})
                                     :Shift-Ctrl-5 (cmd/setBlockType heading {:level 5})
                                     :Shift-Ctrl-6 (cmd/setBlockType heading {:level 6})}

                                    {:Mod-z cmd/undo
                                     :Shift-Mod-z cmd/redo}
                                    (when-not mac?
                                      {:Mod-y cmd/redo})

                                    {[:Mod-Enter
                                      :Shift-Enter] hard-break-cmd}
                                    (when mac?
                                      {:Ctrl-Enter hard-break-cmd})

                                    {:Mod-_ (fn [^js state dispatch]
                                              (when dispatch
                                                (dispatch (.. state -tr
                                                              (replaceSelectionWith (.create horizontal_rule))
                                                              (cmd/scrollIntoView))))
                                              true)}

                                    )))))