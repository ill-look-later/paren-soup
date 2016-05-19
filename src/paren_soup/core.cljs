(ns paren-soup.core
  (:require [clojure.string :refer [join replace]]
            [goog.events :as events]
            [goog.functions :refer [debounce]]
            [goog.string :refer [format]]
            [cljsjs.rangy-core]
            [cljsjs.rangy-textrange]
            [schema.core :refer [maybe either Any Str Int Keyword Bool]]
            [mistakes-were-made.core :as mwm]
            [html-soup.core :as hs]
            [paren-soup.fx :as fx])
  (:require-macros [schema.core :refer [defn with-fn-validation]]))

(defn show-error-message!
  "Shows a popup with an error message."
  [parent-elem :- js/Object
   event :- js/Object]
  (let [elem (.-target event)
        x (.-clientX event)
        y (.-clientY event)
        popup (.createElement js/document "div")]
    (aset popup "textContent" (-> elem .-dataset .-message))
    (aset (.-style popup) "top" (str y "px"))
    (aset (.-style popup) "left" (str x "px"))
    (aset popup "className" "error-text")
    (.appendChild parent-elem popup)))

(def show-error-icon!
  (debounce
    (fn [elem]
      (set! (.-display (.-style elem)) "inline-block"))
    1000))

(defn hide-error-messages!
  "Hides all error popups."
  [parent-elem :- js/Object]
  (doseq [elem (-> parent-elem (.querySelectorAll ".error-text") array-seq)]
    (.removeChild parent-elem elem)))

(defn elems->locations :- [{Keyword Any}]
  "Returns the location of each elem."
  [elems :- [js/Object]
   top-offset :- Int]
  (loop [i 0
         locations (transient [])]
    (if-let [elem (get elems i)]
      (let [top (-> elem .-offsetTop (- top-offset))
            height (-> elem .-offsetHeight)]
        (recur (inc i) (conj! locations {:top top :height height})))
      (persistent! locations))))

(defn results->html :- Str
  "Returns HTML for the given eval results."
  [results :- [Any]
   locations :- [{Keyword Any}]]
  (loop [i 0
         evals (transient [])]
    (let [res (get results i)
          {:keys [top height]} (get locations i)]
      (if (and res top height)
        (recur (inc i)
               (conj! evals
                 (format
                   "<div class='%s' style='top: %spx; height: %spx; min-height: %spx'>%s</div>"
                   (if (array? res) "result error" "result")
                   top
                   height
                   height
                   (some-> (if (array? res) (first res) res)
                           hs/escape-html-str))))
        (join (persistent! evals))))))

(defn get-collections :- [js/Object]
  "Returns collections from the given DOM node."
  [content :- js/Object]
  (vec (for [elem (-> content .-children array-seq)
             :let [classes (.-classList elem)]
             :when (or (.contains classes "collection")
                       (.contains classes "symbol"))]
         elem)))

(def ^:const rainbow-count 10)

(defn rainbow-delimiters :- Any
  "Returns a map of elements and class names."
  ([parent :- js/Object
    level :- Int]
   (persistent! (rainbow-delimiters parent level (transient {}))))
  ([parent :- js/Object
    level :- Int
    m :- Any]
   (reduce
     (fn [m elem]
       (let [classes (.-classList elem)]
         (cond
           (.contains classes "delimiter")
           (assoc! m elem (str "rainbow-" (mod level rainbow-count)))
           (.contains classes "collection")
           (rainbow-delimiters elem (inc level) m)
           :else
           m)))
     m
     (-> parent .-children array-seq))))

(defn line-numbers :- Str
  "Adds line numbers to the numbers."
  [line-count :- Int]
  (join (for [i (range line-count)]
          (str "<div>" (inc i) "</div>"))))

(defn get-selection :- {Keyword Any}
  "Returns the objects related to selection for the given element."
  [content :- js/Object]
  (let [selection (.getSelection js/rangy)
        ranges (.saveCharacterRanges selection content)
        char-range (some-> ranges (aget 0) (aget "characterRange"))]
    {:selection selection :ranges ranges :char-range char-range}))

(defn get-cursor-position :- [Int]
  "Returns the cursor position."
  [content :- js/Object]
  (if-let [range (some-> content get-selection :char-range)]
    [(aget range "start") (aget range "end")]
    [0 0]))

(defn set-cursor-position!
  "Moves the cursor to the specified position."
  [content :- js/Object
   &
   [start-pos :- Int
    end-pos :- Int]]
  (let [{:keys [selection ranges char-range]} (get-selection content)]
    (when (and selection ranges char-range)
      (aset char-range "start" start-pos)
      (aset char-range "end" (or end-pos start-pos))
      (.restoreCharacterRanges selection content ranges))))

(defn refresh-numbers!
  "Refreshes the line numbers."
  [numbers :- js/Object
   line-count :- Int]
  (set! (.-innerHTML numbers) (line-numbers line-count)))

(defn refresh-instarepl!
  "Refreshes the InstaREPL."
  [instarepl :- js/Object
   content :- js/Object
   eval-worker :- js/Object]
  (let [elems (get-collections content)
        locations (elems->locations elems (.-offsetTop instarepl))
        forms (into-array (map #(-> % .-textContent (replace \u00a0 " ")) elems))]
    (set! (.-onmessage eval-worker)
          (fn [e]
            (let [results (.-data e)]
              (when (some-> elems first .-parentNode)
                (set! (.-innerHTML instarepl)
                      (results->html results locations))))))
    (.postMessage eval-worker forms)))

(defn post-refresh-content!
  "Does additional work on the content after it is rendered."
  [paren-soup :- js/Object
   content :- js/Object
   state :- {Keyword Any}]
  ; set the cursor position
  (let [[start-pos end-pos] (:cursor-position state)]
    (set-cursor-position! content start-pos end-pos))
  ; set up errors
  (hide-error-messages! (.-parentElement content))
  (doseq [elem (-> content (.querySelectorAll ".error") array-seq)]
    (show-error-icon! elem)
    (events/listen elem "mouseenter"
      (fn [event]
        (show-error-message! paren-soup event)))
    (events/listen elem "mouseleave"
      (fn [event]
        (hide-error-messages! paren-soup))))
  ; add rainbow delimiters
  (doseq [[elem class-name] (rainbow-delimiters content -1)]
    (.add (.-classList elem) class-name)))

(defn refresh-content!
  "Refreshes the content."
  [content :- js/Object
   state :- {Keyword Any}]
  (set! (.-innerHTML content) (fx/code->html (:text state))))

(defn adjust-state :- {Keyword Any}
  "Adds a newline and indentation to the state if necessary."
  [state :- {Keyword Any}]
  (let [{:keys [text indent-type]} state
        state (if-not (= \newline (last text))
                (assoc state :text (str text \newline))
                state)
        state (if indent-type
                (fx/add-indent state)
                state)]
    state))

(defn init-state :- {Keyword Any}
  "Returns the editor's state after sanitizing it."
  [content :- js/Object]
  (let [pos (get-cursor-position content)
        text (.-textContent content)]
    {:cursor-position pos
     :text text}))

(defn key-name? :- Bool
  "Returns true if the supplied key event involves the key(s) described by key-name."
  [event :- js/Object
   key-name :- Keyword]
  (case key-name
    :undo-or-redo
    (and (or (.-metaKey event) (.-ctrlKey event))
       (= (.-keyCode event) 90))
    :tab
    (= (.-keyCode event) 9)
    :enter
    (= (.-keyCode event) 13)
    :arrows
    (contains? #{37 38 39 40} (.-keyCode event))
    :general
    (not (or (contains? #{16 ; shift
                          17 ; ctrl
                          18 ; alt
                          91 93} ; meta
                         (.-keyCode event))
             (.-ctrlKey event)
             (.-metaKey event)))
    false))

(defn init! []
  (.init js/rangy)
  (doseq [paren-soup (-> js/document (.querySelectorAll ".paren-soup") array-seq)]
    (let [instarepl (.querySelector paren-soup ".instarepl")
          numbers (.querySelector paren-soup ".numbers")
          content (.querySelector paren-soup ".content")
          eval-worker (when instarepl (js/Worker. "paren-soup-compiler.js"))
          edit-history (mwm/create-edit-history)
          current-state (atom nil)
          refresh-instarepl-with-delay! (debounce refresh-instarepl! 300)]
      (set! (.-spellcheck paren-soup) false)
      (when-not content
        (throw (js/Error. "Can't find a div with class 'content'")))
      ; refresh the editor every time the state is changed
      (add-watch current-state :render
        (fn [_ _ _ state]
          (refresh-content! content state)
          (post-refresh-content! paren-soup content state)
          (some-> numbers (refresh-numbers! (count (re-seq #"\n" (:text state)))))
          (some-> instarepl (refresh-instarepl-with-delay! content eval-worker))))
      ; initialize the editor
      (->> (init-state content)
           (fx/add-parinfer :paren)
           (adjust-state)
           (reset! current-state)
           (mwm/update-edit-history! edit-history))
      ; remove any previously-attached event listeners
      (events/removeAll content)
      ; update the state on keydown
      (events/listen content "keydown"
        (fn [event]
          (cond
            (key-name? event :undo-or-redo)
            (if (.-shiftKey event)
              (when-let [state (mwm/redo! edit-history)]
                (reset! current-state (adjust-state state)))
              (when-let [state (mwm/undo! edit-history)]
                (reset! current-state (adjust-state state))))
            (key-name? event :enter)
            (.execCommand js/document "insertHTML" false "\n"))
          (when (or (key-name? event :undo-or-redo)
                    (key-name? event :tab)
                    (key-name? event :enter))
            (.preventDefault event))))
      ; update the state on keyup
      (events/listen content "keyup"
        (fn [event]
          (cond
            (key-name? event :arrows)
            (mwm/update-cursor-position! edit-history (get-cursor-position content))
            (key-name? event :general)
            (let [state (init-state content)]
              (->> (case (.-keyCode event)
                     13 (assoc  state :indent-type :return)
                     9 (assoc state :indent-type (if (.-shiftKey event) :back :forward))
                     (fx/add-parinfer :indent state))
                   (adjust-state)
                   (reset! current-state)
                   (mwm/update-edit-history! edit-history))))))
      ; update the cursor position in the edit history on mouseup
      (events/listen content "mouseup"
        (fn [event]
          (mwm/update-cursor-position! edit-history (get-cursor-position content))))
      ; refresh the editor with *both* parinfer modes on cut/paste
      (let [cb (fn [event]
                 (->> (init-state content)
                      (fx/add-parinfer :both)
                      (adjust-state)
                      (reset! current-state)
                      (mwm/update-edit-history! edit-history)))]
        (events/listen content "cut" cb)
        (events/listen content "paste" cb)))))

(defn init-debug! []
  (.log js/console (with-out-str (time (with-fn-validation (init!))))))

(set! (.-onload js/window) init!)
