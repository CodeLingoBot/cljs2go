;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

;; Go emitter. Forked from f0dcc75573a42758f8c39b57d1747a2b4967327e.
;; References to js in the public API are retained.

(ns cljs.go.compiler
  (:refer-clojure :exclude [munge macroexpand-1])
  (:require [cljs.util :as util]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.reader :as reader]
            [cljs.env :as env]
            [cljs.tagged-literals :as tags]
            [cljs.analyzer :as ana])
  (:import java.lang.StringBuilder
           java.io.File))

(set! *warn-on-reflection* true)

(alter-var-root #'ana/*cljs-macros-path* (constantly "/cljs/go/core"))

(defn clojurescript-to-go-version
  "Returns clojurescript version as a printable string."
  []
  (-> (System/getProperty "java.class.path")
      (string/split #":")
      (->> (some #(re-find #"clojurescript-(.+)\.jar" %))
           second)))

(def go-types
  #{"uint8" "uint16" "uint32" "uint64"
    "int8" "int16" "int32" "int64"
    "float32" "float64"
    "complex64" "complex128"
    "byte" "rune"
    "int" "float"})

(def go-reserved
  (into go-types
   #{"break" "case" "chan" "const" "continue" "default"
     "defer" "else" "fallthrough" "for" "func" "go" "goto"
     "if" "import" "interface" "map" "package" "range"
     "return" "select" "struct" "switch" "type" "var"}))

(def ^:dynamic *lexical-renames* {})

(def cljs-reserved-file-names #{"deps.cljs"})

(def go-cljs-import-prefix "github.com/hraberg/cljs2go/")
(def ^:dynamic *go-import-prefix* (zipmap '[goog
                                            goog.string
                                            goog.array
                                            goog.object
                                            js
                                            js.Math
                                            cljs.core
                                            cljs.reader
                                            clojure.data
                                            clojure.set
                                            clojure.string
                                            clojure.walk
                                            clojure.zip

                                            ;; The compiler
                                            cljs.js-deps
                                            cljs.env
                                            cljs.analyzer
                                            cljs.util
                                            cljs.tagged-literals
                                            cljs.go.compiler
                                            cljs.go.core
                                            clojure.java.io
                                            clojure.tools.reader
                                            clojure.tools.reader.reader-types
                                            java.io
                                            java.lang]
                                          (repeat go-cljs-import-prefix)))
(def ^:dynamic *go-verbose* true)
(def ^:dynamic *go-return-name* nil)
(def ^:dynamic *go-return-tag* nil)
(def ^:dynamic *go-protocol-fn* nil)
(def ^:dynamic *go-protocol-this* nil)
(def ^:dynamic *go-protocol* nil)
(def ^:dynamic *go-loop-vars* #{})
(def ^:dynamic *go-defs* nil)
(def ^:dynamic *go-def-vars* false)
(def ^:dynamic *go-assign-vars* true)
(def ^:dynamic *go-dot* false)
(def ^:dynamic *go-line-numbers* false) ;; https://golang.org/cmd/gc/#hdr-Compiler_Directives
(def ^:dynamic *go-skip-def*
  '#{cljs.core/*clojurescript-version*
     cljs.core/enable-console-print!
     cljs.core/*print-length*
     cljs.core/*print-level*
     cljs.core/set-print-fn!
     cljs.core/string-hash-cache
     cljs.core/add-to-string-hash-cache
     cljs.core/hash-string
     cljs.core/object?
     cljs.core/native-satisfies?
     cljs.core/instance?
     cljs.core/symbol?
     cljs.core/=
     cljs.core/sort
     cljs.core/rand
     cljs.core/missing-protocol
     cljs.core/complement
     cljs.core/remove
     cljs.core/identity
     cljs.core/make-array
     cljs.core/array
     cljs.core/integer?
     cljs.core/char
     cljs.core/apply
     cljs.core/get
     cljs.core/truth_
     cljs.core/is_proto_
     cljs.core/type
     cljs.core/type->str
     cljs.core/char-escapes
     cljs.core/quote-string
     cljs.core/pr-writer
     cljs.core/pr-sequential-writer
     cljs.core/obj-map
     cljs.core/obj-clone
     cljs.core/js-obj
     cljs.core/js-keys
     cljs.core/js->clj
     cljs.core/key->js
     cljs.core/clj->js
     cljs.core/test
     cljs.reader/read-2-chars
     cljs.reader/read-4-chars
     cljs.reader/read-token
     cljs.reader/macros
     cljs.reader/days-in-month
     cljs.reader/parse-timestamp
     cljs.reader/read-queue
     cljs.reader/read-date
     cljs.reader/read-uuid
     cljs.reader/read-js
     cljs.reader/*tag-table*
     clojure.string/replace
     clojure.string/replace-first
     clojure.data/diff})
(def ^:dynamic *go-skip-protocol* '{cljs.core/TransientArrayMap #{cljs.core/ITransientMap}
                                    cljs.core/PersistentTreeSet #{cljs.core/ISorted}})

(defmacro ^:private debug-prn
  [& args]
  `(.println System/err (str ~@args)))

(defn ns-first-segments []
  (letfn [(get-first-ns-segment [ns] (first (string/split (str ns) #"\.")))]
    (map get-first-ns-segment (keys (::ana/namespaces @env/*compiler*)))))

; Helper fn
(defn shadow-depth [s]
  (let [{:keys [name info]} s]
    (loop [d 0, {:keys [shadow]} info]
      (cond
       shadow (recur (inc d) shadow)
       (some #{(str name)} (ns-first-segments)) (inc d)
       :else d))))

(defn munge
  ([s] (munge s go-reserved))
  ([s reserved]
    (if (map? s)
      ; Unshadowing
      (let [{:keys [name field] :as info} s
            depth (shadow-depth s)
            renamed (*lexical-renames* (System/identityHashCode s))
            munged-name (munge (cond field (str (munge (:name *go-protocol-this*)) "." name)
                                     renamed renamed
                                     :else name)
                               reserved)]
        (if (or field (zero? depth))
          munged-name
          (symbol (str munged-name "___" depth))))
      ; String munging
      (let [ss (string/replace (str s) #"\/(.)" ".$1") ; Division is special
            ss (if (= ".." ss)
                 "_DOT__DOT_"
                 ss)
            ss (string/replace ss "$" "_")
            ss (apply str (map #(if (reserved %) (str % "_") %)
                               (string/split ss #"(?<=\.)|(?=\.)")))
            ms (string/split (clojure.lang.Compiler/munge ss) #"\.")
            ms (if (butlast ms)
                 (str (string/join "_" (butlast ms)) "." (last ms))
                 (str (last ms)))
            ms (cond-> ms (= "_" ms) (str "__"))]
        (if (symbol? s)
          (symbol ms)
          ms)))))

(defn- comma-sep [xs]
  (interpose "," xs))

(defn- escape-char [^Character c]
  (let [cp (.hashCode c)]
    (case cp
      ; Handle printable escapes before ASCII
      34 "\\\""
      92 "\\\\"
      ; Handle non-printable escapes
      8 "\\b"
      12 "\\f"
      10 "\\n"
      13 "\\r"
      9 "\\t"
      (if (< 31 cp 127)
        c ; Print simple ASCII characters
        (format "\\u%04X" cp))))) ; Any other character is Unicode

(defn- escape-string [^CharSequence s]
  (let [sb (StringBuilder. (count s))]
    (doseq [c s]
      (.append sb (escape-char c)))
    (.toString sb)))

(defn- wrap-in-double-quotes [x]
  (str \" x \"))

(defn go-public [s]
  (let [s (name s)]
    (when (seq s)
      (if (= \_ (first s))
        (str "X" s)
        (str (string/upper-case (subs s 0 1)) (when (next s) (subs s 1)))))))

(defn go-type-fqn [s]
  (if (and (symbol? s) (= "js" (namespace s)))
    (name s)
    (apply str (map go-public (string/split (str s) #"[./]")))))

(defn go-short-name [s]
  (last (string/split (str s) #"\.")))

(defn go-normalize-goog-type [type]
  (let [parts (string/split (str type) #"\.")
        ns (string/replace (apply str (butlast parts)) "/" ".")]
    (symbol ns (last parts))))

(defn go-core [x]
  (if (= 'cljs.core ana/*cljs-ns*)
    x
    (str "cljs_core." x)))

(def go-primitive '{number "float64" boolean "bool" array "[]interface{}"})

(defn go-goog? [ns]
  (when ns
    (or (= ns 'goog)
        (when-let [ns-str (str ns)]
          (= (get (string/split ns-str #"\.") 0 nil) "goog")))))

(defn go-type
  ([tag] (go-type tag true))
  ([tag native-string?]
     (if-let [ns (and (symbol? tag) (namespace tag))]
       (let [ns (symbol ns)
             goog? (go-goog? ns)
             js? (= 'js ns)
             type? (get-in (ana/get-namespace ns) [:defs (symbol (name tag)) :type])]
         (if (= 'cljs.core/not-native tag) ;; this is a hack, should be dealt with somewhere else, comes from core.clj macros.
           "interface{}"
           (str
            (when (or type? goog? js?) "*")
            (munge (cond
                    (= ana/*cljs-ns* ns) (go-type-fqn tag)
                    goog? (go-normalize-goog-type tag)
                    js? tag
                    :else (str ns "." (go-type-fqn tag)))))))
       (if (or (string? tag) (and (symbol? tag) (go-types (name tag))))
         tag
         ((merge go-primitive
                 (when native-string?
                   {'string "string"})
                 {'seq (go-core "CljsCoreISeq") 'function (go-core "CljsCoreIFn")})
          tag "interface{}")))))

(defn go-short-type [tag]
  ('{number "F" boolean "B" array "A" seq "Q"} tag "I"))

(defn go-type-suffix [params ret-tag]
  (apply str (concat (map (comp go-short-type :tag) params) [(go-short-type ret-tag)])))

;; this is vastly oversimplistic.
(defn go-needs-coercion? [from to]
  (and (not (= (go-type to) "interface{}"))
       (not= (go-type from) (go-type to))
       ;; strings are indexable, but this is a dubious way to deal with it
       (not (and (= 'string from) (= 'array to)))))

(declare emit-str)

(defn go-static-field? [x]
  (and (= :dot (:op x)) (-> x :target :info :type)))

(defn go-unbox-no-emit [to x]
  (let [static-field? (go-static-field? x)
        defined-var? (and (= :var (:op x)) (not (some (:info x) [:local :binding-form? :field])))
        new? (= :new (:op x))
        tag (:tag x)]
    (when (and (go-needs-coercion? tag to)
               (not (or static-field? defined-var? new?)))
      (str ".(" (go-type to) ")"))))

(defn go-unbox [to x]
  (when x
    (str (emit-str x) (go-unbox-no-emit to x))))

;; hack using the positional factory to get the fields, only :num-fields is stored in @env/*compiler*
(defn go-fields-of-type [type]
  (when (symbol? type)
    (first (:method-params (get-in (ana/get-namespace (or (some-> type namespace symbol)
                                                          ana/*cljs-ns*))
                                   [:defs (symbol (str '-> (name type)))])))))

(defn go-tag-of-target [{:keys [op info] :as target}]
  (case op
    :dot
    (or
     (some->> target :target :tag go-fields-of-type
              (some #(and (= % (:field target)) (:tag (meta %)))))
     'any)

    :var
    (if (:field info)
      (:tag info)
      'any)

    nil))

(def go-native-decorator {'string (fn [s] (str "js.JSString_(" (emit-str s) ")"))
                          'array (fn [a] (str "js.JSArray_(&" (emit-str a) ")"))
                          'boolean (fn [s] (str "js.JSBoolean(" (emit-str s) ")"))
                          'number (fn [s] (str "js.JSNumber(" (emit-str s) ")"))
                          'clj-nil (fn [s] (str "js.JSNil{}"))})

(def go-skip-set! '#{(set! (.-prototype ExceptionInfo) (js/Error.))
                     (set! (.. ExceptionInfo -prototype -constructor) ExceptionInfo)
                     (set! (.-EMPTY ObjMap) (ObjMap. nil (array) (js-obj) 0 0))
                     (set! (.-HASHMAP_THRESHOLD ObjMap) 8)
                     (set! (.-fromObject ObjMap) (fn [ks obj] (ObjMap. nil ks obj 0 nil)))})

(defn warn-on-reflection [{:keys [env field method f]}]
  (when *warn-on-reflection*
    (binding [*out* *err*]
      (let [{:keys [file column line] :or {file "-"}} (ana/source-info env)]
        (cond
         field (printf "Reflection warning, %s:%d:%d - reference to field %s can't be resolved.\n"
                       file line column field)
         method (printf "Reflection warning, %s:%d:%d - call to method %s can't be resolved (target class is unknown).\n"
                        file line column method)
         f (printf "Reflection warning, %s:%d:%d - call to method %s can't be resolved (target class is unknown).\n"
                   file line column (-> f :info :name)))))))

(defmulti emit* :op)

(defn emit [ast]
  (env/ensure
   (emit* ast)))

(defn emits [& xs]
  (doseq [x xs]
    (cond
     (nil? x) nil
     (map? x) (emit x)
     (seq? x) (apply emits x)
     (fn? x)  (x)
     :else (let [s (print-str x)]
             (print s))))
  nil)

(defn emitln [& xs]
  (when-let [line (and *go-line-numbers*  (some (comp :line :env) xs))]
    (printf "\n//line %s:%d\n" ana/*cljs-file* line))
  (apply emits xs)
  (println)
  nil)

(defn ^String emit-str [expr]
  (with-out-str (emit expr)))

(defmulti emit-constant class)
(defmethod emit-constant nil [x] (emits "nil"))
(defmethod emit-constant Long [x] (emits "float64(" x ")"))
(defmethod emit-constant Integer [x] (emits "float64(" x ")")) ; reader puts Integers in metadata
(defmethod emit-constant Double [x] (emits x))
(defmethod emit-constant BigDecimal [x] (emits (.doubleValue ^BigDecimal x)))
(defmethod emit-constant clojure.lang.BigInt [x] (emits (.doubleValue ^clojure.lang.BigInt x)))
(defmethod emit-constant String [x]
  (emits (wrap-in-double-quotes (escape-string x))))
(defmethod emit-constant Boolean [x] (emits (if x "true" "false")))
(defmethod emit-constant Character [x]
  (emits (wrap-in-double-quotes (escape-char x))))

(defmethod emit-constant java.util.regex.Pattern [x]
  (if (= "" (str x))
    (emits "(&js.RegExp{Pattern:``, Flags: ``})")
    (let [[_ flags pattern] (re-find #"^(?:\(\?([idmsux]*)\))?(.*)" (str x))]
      (emits "(&js.RegExp{Pattern: `" (.replaceAll (re-matcher #"/" pattern) "\\\\/") "`, Flags: `" flags "`})"))))

(defn emits-keyword [kw]
  (let [ns   (namespace kw)
        name (name kw)]
    (emits "(&" (go-core "CljsCoreKeyword") "{")
    (emits "Ns: ")
    (emit-constant ns)
    (emits ",")
    (emits "Name: ")
    (emit-constant name)
    (emits ",")
    (emits "Fqn: ")
    (emit-constant (if ns
                     (str ns "/" name)
                     name))
    (emits ",")
    (emits "X_hash: ")
    (emit-constant (hash kw))
    (emits "})")))

(defmethod emit-constant clojure.lang.Keyword [x]
  (if (-> @env/*compiler* :opts :emit-constants)
    (let [value (-> @env/*compiler* ::ana/constant-table x)]
      (emits value))
    (emits-keyword x)))

(defmethod emit-constant clojure.lang.Symbol [x]
  (let [ns     (namespace x)
        name   (name x)
        symstr (if-not (nil? ns)
                 (str ns "/" name)
                 name)]
    (emits "(&" (go-core "CljsCoreSymbol") "{")
    (emits "Ns: ")
    (emit-constant ns)
    (emits ",")
    (emits "Name: ")
    (emit-constant name)
    (emits ",")
    (emits "Str: ")
    (emit-constant symstr)
    (emits ",")
    (emits "X_hash: ")
    (emit-constant (hash x))
    (emits ",")
    (emits "X_meta: ")
    (emit-constant nil)
    (emits "})")))

;; tagged literal support

(defn read-queue
  [form]
  (when-not (vector? form)
    (throw (RuntimeException. "Queue literal expects a vector for its elements.")))
  (list 'cljs.core/into '(.-EMPTY cljs.core/PersistentQueue) form))

(defmethod emit-constant java.util.Date [^java.util.Date date]
  (emits "(&js.Date{Millis: " (.getTime date) "})"))

(defmethod emit-constant java.util.UUID [^java.util.UUID uuid]
  (emits "(&" (go-core "CljsCoreUUID") "{Uuid: `" (.toString uuid) "`})"))

(defmacro emit-wrap [env & body]
  `(let [env# ~env]
     (when (= :return (:context env#))
       (if-let [out# *go-return-name*]
         (emits out# " = ")
         (emits "return ")))
     ~@body
     (when (= :return (:context env#))
       (when-let [tag# *go-return-tag*]
         (emits ".(" (go-type tag#) ")")))
     (when-not (= :expr (:context env#)) (emitln))))

(defmethod emit* :no-op [m])

(defmethod emit* :var
  [{:keys [info env tag] :as arg}]
  (let [ns (and (symbol? (:name info)) (some-> info :name namespace symbol))
        info (cond-> info (and *go-dot* (:type info)) (update-in [:name] #(symbol (str ns) (go-type-fqn %))))
        statement? (= :statement (:context env))]
    ; We need a way to write bindings out to source maps and javascript
    ; without getting wrapped in an emit-wrap calls, otherwise we get
    ; e.g. (function greet(return x, return y) {}).
    (cond
     (= (:name info) *go-protocol-fn*) ;; self reference to protocol fn as var.
     (emits (when (= 'cljs.core/Object *go-protocol*)
              (str "(" (go-type (:tag *go-protocol-this*)) ")."))
            (-> info :name munge go-public))

     (:binding-form? arg)
                                        ; Emit the arg map so shadowing is properly handled when munging
                                        ; (prevents duplicate fn-param-names)
     (emits (munge arg))

     (and (or ('#{js/Date js/RegExp js/Error js/TypeError} (-> info :name))
              (:type info)
              (:protocol-symbol info))
          (not (or *go-dot* statement?)))
     (emits "reflect.TypeOf((*" (go-type (:name info)) ")(nil)).Elem()")

     :else
     (when-not statement?
       (binding [*go-return-tag* (when (go-needs-coercion? tag *go-return-tag*)
                                   *go-return-tag*)]
         (emit-wrap env (emits (munge (cond ;; this runs munge in a different order from most other things.
                                       (or (= ana/*cljs-ns* ns)
                                           (:field info))
                                       (update-in info [:name] (comp go-public munge name))
                                       ns
                                       (update-in info [:name] #(str ns "." (-> % name munge go-public)))
                                       :else info)))))))))

(defmethod emit* :var-special
  [{:keys [env var sym meta] :as arg}]
  (emit-wrap env
    (emits "(&" (go-core "CljsCoreVar") "{")
    (emits "Val: ")
    (emit var)
    (emits ",")
    (emits "Sym: ")
    (emit sym)
    (emits ",")
    (emits "X_meta: ")
    (emit meta)
    (emits "})")))

(defmethod emit* :meta
  [{:keys [expr meta env]}]
  (emit-wrap env
    (emits (go-core "With_meta") ".X_invoke_Arity2(" expr "," meta ")")))

(def ^:private array-map-threshold 8)
(def ^:private obj-map-threshold 8)

(defn distinct-keys? [keys]
  (and (every? #(= (:op %) :constant) keys)
       (= (count (into #{} keys)) (count keys))))

(defmethod emit* :map
  [{:keys [env keys vals]}]
  (let [simple-keys? (every? #(or (string? %) (keyword? %)) keys)]
    (emit-wrap env
      (cond
        (zero? (count keys))
        (emits (go-core "CljsCorePersistentArrayMap_EMPTY"))

        (<= (count keys) array-map-threshold)
        (if (distinct-keys? keys)
          (emits "(&" (go-core "CljsCorePersistentArrayMap") "{nil, float64(" (count keys) "), []interface{}{"
            (comma-sep (interleave keys vals))
            "}, nil})")
          (emits (go-core "CljsCorePersistentArrayMap_FromArray") ".X_invoke_Arity3([]interface{}{"
            (comma-sep (interleave keys vals))
            "}, true, false).(*" (go-core "CljsCorePersistentArrayMap") ")"))

        :else
        (emits (go-core "CljsCorePersistentHashMap_FromArrays") ".X_invoke_Arity2([]interface{}{"
               (comma-sep keys)
               "},[]interface{}{"
               (comma-sep vals)
               "}).(*" (go-core "CljsCorePersistentHashMap") ")")))))

(defmethod emit* :list
  [{:keys [items env]}]
  (binding [*go-return-tag* nil]
    (emit-wrap env
      (if (empty? items)
        (emits (go-core "CljsCoreIEmptyList") "(" (go-core "CljsCoreList_EMPTY") ")")
        (emits (go-core "List") ".X_invoke_ArityVariadic(" (go-core "Array_seq") ".X_invoke_Arity1([]interface{}{" (comma-sep items) "})).(*"
               (go-core "CljsCoreList") ")")))))

(defmethod emit* :vector
  [{:keys [items env]}]
  (binding [*go-return-tag* nil]
    (emit-wrap env
      (if (empty? items)
        (emits (go-core "CljsCorePersistentVector_EMPTY"))
        (let [cnt (count items)]
          (if (< cnt 32)
            (emits "(&" (go-core "CljsCorePersistentVector") "{nil, float64(" cnt
                   "), float64(5), " (go-core "CljsCorePersistentVector_EMPTY_NODE") ", []interface{}{" (comma-sep items) "}, nil})")
            (emits (go-core "CljsCorePersistentVector_FromArray") ".X_invoke_Arity2([]interface{}{" (comma-sep items) "}, true).(*" (go-core "CljsCorePersistentVector") ")")))))))

(defn distinct-constants? [items]
  (and (every? #(= (:op %) :constant) items)
       (= (count (into #{} items)) (count items))))

(defmethod emit* :set
  [{:keys [items env]}]
  (binding [*go-return-tag* nil]
    (emit-wrap env
      (cond
       (empty? items)
       (emits (go-core "CljsCorePersistentHashSet_EMPTY"))

       (distinct-constants? items)
       (emits "(&" (go-core "CljsCorePersistentHashSet") "{nil, &" (go-core "CljsCorePersistentArrayMap")
              "{nil, float64(" (count items) "), []interface{}{"
              (comma-sep (interleave items (repeat "nil"))) "}, nil}, nil})")

       :else (emits (go-core "CljsCorePersistentHashSet_FromArray") ".X_invoke_Arity2([]interface{}{" (comma-sep items) "}, true).(*" (go-core "CljsCorePersistentHashSet") ")")))))

(defmethod emit* :js-value
  [{:keys [items js-type env]}]
  (emit-wrap env
    (if (= js-type :object)
      (do
        (emits "map[string]interface{}{")
        (when-let [items (seq items)]
          (let [[[k v] & r] items]
            (emits "\"" (name k) "\": " v)
            (doseq [[k v] r]
              (emits ", \"" (name k) "\": " v))))
        (emits "}"))
      (emits "[]interface{}{" (comma-sep items) "}"))))

(defmethod emit* :constant
  [{:keys [form env tag]}]
  (when-not (= :statement (:context env))
    (binding [*go-return-tag* (when (and (go-needs-coercion? tag *go-return-tag*)
                                         (not (nil? form)))
                                *go-return-tag*)]
      (emit-wrap env (emit-constant form)))))

(defn truthy-constant? [{:keys [op form]}]
  (and (#{:constant :list :vector :set :map} op) form))

(defn falsey-constant? [{:keys [op form]}]
  (and (= op :constant) (not form)))

(defn safe-test? [env e]
  (= 'boolean (:tag e)))

(defmethod emit* :if
  [{:keys [test then else env form unchecked tag]}]
  (let [context (:context env)
        checked (not (or unchecked (safe-test? env test)))
        checked (if-let [real-tag (go-tag-of-target test)]
                  (not= real-tag 'boolean)
                  checked)
        test (cond-> test checked (assoc :tag 'any))] ;; and/or can mess up the tags - will always be interface{} for checked.
    (cond
      (truthy-constant? test) (emitln then)
      (falsey-constant? test) (emitln else)
      :else
      (if (= :expr context)
        (emits "func() " (go-type tag) " { if " (when checked (go-core "Truth_")) "("
               test
               ") { return " then "} else { return " else "} }()")
        (do
          (if checked
            (emitln "if " (go-core "Truth_") "(" test ") {")
            (emitln "if " test " {"))
          (emitln then "} else {")
          (emitln else "}"))))))

(defmethod emit* :case*
  [{:keys [v tests thens default env tag]}]
  (when (= (:context env) :expr)
    (emitln "func() " (go-type tag) " {"))
  (let [gs (gensym "caseval__")]
    (when (= :expr (:context env))
      (emitln "var " gs ""))
    (emitln "switch " v " {")
    (doseq [[ts then] (partition 2 (interleave tests thens))]
      (emitln "case " (comma-sep ts) ":")
      (if (= :expr (:context env))
        (emitln gs "=" then)
        (emitln then)))
    (when default
      (emitln "default:")
      (if (= :expr (:context env))
        (emitln gs "=" default)
        (emitln default)))
    (emitln "}")
    (when (= :expr (:context env))
      (emitln "return " gs "}()"))))

(defmethod emit* :throw
  [{:keys [throw env]}]
  (if (= :expr (:context env))
    (emits "func() " (go-type (:tag throw)) " { panic("  throw ") }()")
    (emitln "panic(" throw ")")))

(defn emit-comment
  "Emit a nicely formatted comment string."
  [doc jsdoc]
  (let [docs (when doc [doc])
        docs (if jsdoc (concat docs jsdoc) docs)
        docs (remove nil? docs)]
    (letfn [(print-comment-lines [e] (doseq [next-line (string/split-lines e)]
                                       (emitln "// " (string/trim next-line))))]
      (when (seq docs)
        (doseq [e docs]
          (when e
            (print-comment-lines e)))))))

(defn untyped-nil-needs-type? [init]
  (or (= 'clj-nil (:tag init)) (nil? init)))

(defmethod emit* :def
  [{:keys [name var init env doc export tag form] :as ast}]
  (let [protocol-symbol? (-> form second meta :protocol-symbol)
        declared? (-> form second meta :declared)]
    (when-not (or (*go-skip-def* name) protocol-symbol? declared?)
      (let [mname (-> name munge go-short-name go-public)
            fn? (= :fn (:op init))
            afn-type (str "*" (go-core "AFn"))
            def-type (if (= 'function tag)
                       afn-type
                       (go-type (when-not (-> init :info :fn-var) tag)))]
        (some-> *go-defs* (swap! conj ast))
        (when *go-def-vars*
          (emit-comment doc (:jsdoc init))
          (emits "var " mname " " def-type))
        (if-let [init (and *go-assign-vars*
                           (if init (emit-str init) ('{number "-1.0" boolean "false" clj-nil "nil"} tag)))]
          (emitln (when (not *go-def-vars*) mname) " = " init
                  (when (and (= 'function tag) (not fn?))
                    (go-unbox-no-emit afn-type nil)))
          (emitln))
        ;; NOTE: JavaScriptCore does not like this under advanced compilation
        ;; this change was primarily for REPL interactions - David
                                        ;(emits " = (typeof " mname " != 'undefined') ? " mname " : undefined")
        (when-not (= :expr (:context env)) (emitln))
        ;; TODO: deal with tests
        ;; (when (and ana/*load-tests* (:test var))
        ;;   (when (= :expr (:context env))
        ;;     (emitln ";"))
        ;;   (emits var ".cljs$lang$test = " (:test var)))
        ))))

(defn typed-params [params native-string?]
  (for [param params]
    (str (emit-str param) " " (if (*go-loop-vars* (:name param))
                                (-> param :tag (go-primitive "interface{}"))
                                (-> param :tag (go-type native-string?))))))

(defn emit-fn-signature [params ret-tag native-string?]
  (emits "(" (comma-sep (typed-params params native-string?)) ") " (go-type ret-tag native-string?)))

(defn assign-to-blank [bindings]
  (when-let [bindings (seq (remove '#{_} (map munge bindings)))]
    (emitln (comma-sep (repeat (count bindings) "_"))
            " = "
            (comma-sep bindings))))

(defn emit-fn-body [type expr recurs]
  (when recurs
    (emitln "for {"))
  (emits expr)
  (when recurs
    (emitln "}")))

(defn emit-fn-method
  [{:keys [type params expr env recurs]} ret-tag]
  (emit-wrap env
    (emits "func")
    (emit-fn-signature params ret-tag false)
    (emits "{")
    (binding [*go-return-tag* (when (go-needs-coercion? (:tag expr) ret-tag)
                                ret-tag)]
      (emit-fn-body type expr recurs))
    (emits "}")))

(defn emit-protocol-method
  [protocol name {:keys [type params expr env recurs]} ret-tag]
  (let [object? (= 'cljs.core/Object protocol)
        ifn? (= 'cljs.core/IFn protocol)]
    (emits "func (" (first params) " " (go-type (symbol (str ana/*cljs-ns*) (str type))) ") "
           (-> name munge go-short-name go-public)
           (when-not object?
             (str "_Arity" (cond-> (count params)
                                   (and ifn?
                                        (= '-invoke (:name name))) dec))))
    (emit-fn-signature (rest params) ret-tag object?)
    (emits "{")
    (binding [*go-return-tag* (when (go-needs-coercion? (:tag expr) ret-tag)
                                ret-tag)
              *go-protocol* protocol
              *go-protocol-fn* (:name name)
              *go-protocol-this* (first params)]
      (emit-fn-body type expr recurs))
    (emitln "}")))

(defn emit-variadic-fn-method
  [{:keys [type name variadic params expr env recurs max-fixed-arity] :as f}]
  (let [varargs (munge (str (string/join "_" (map :name params)) "__"))]
    (emit-wrap env
      (emits "func(")
      (emitln varargs " ...interface{}" ") interface{} {")
      (doseq [[idx p] (map-indexed vector (butlast params))]
        (emitln "var " p " = " varargs "[" idx "]"))
      (emitln "var " (last params) " = " (go-core "Seq") ".Arity1IQ(" varargs "[" max-fixed-arity "]" ")")
      (assign-to-blank params)
      (emit-fn-body type expr recurs)
      (emits "}"))))

(defmethod emit* :fn
  [{:keys [name env methods protocol-impl max-fixed-arity variadic recur-frames loop-lets] :as ast}]
  ;;fn statements get erased, serve no purpose and can pollute scope if named
  (when (or (not= :statement (:context env)) protocol-impl)
    (if (and protocol-impl (not *go-def-vars*))
      (swap! *go-defs* conj ast)
      (let [loop-locals (->> (concat (mapcat :params (filter #(and % @(:flag %)) recur-frames))
                                     (mapcat :params loop-lets))
                             seq)
            name (or name (gensym))
            mname (munge name)]
        (when (= :return (:context env))
          (emits "return "))
        (when-not protocol-impl
          (emitln "func(" (comma-sep (cons (str mname " *" (go-core "AFn"))
                                           (typed-params loop-locals true))) ") *" (go-core "AFn") " {")
          (emits "return " (go-core "Fn") "(" mname ", " max-fixed-arity ", "))
        (loop [[meth & methods] methods]
          (let [meth (assoc-in meth [:env :context] :expr)]
            (cond
             protocol-impl (emit-protocol-method protocol-impl name meth (:ret-tag name))
             (:variadic meth) (emit-variadic-fn-method meth)
             :else (emit-fn-method meth (:ret-tag name))))
          (when methods
            (emits ", ")
            (recur methods)))
        (if protocol-impl
          (emitln)
          (do
            (emitln ")")
            (emits "}(" (comma-sep (cons (str "&" (go-core "AFn") "{}") loop-locals)) ")")))))))

(defmethod emit* :do
  [{:keys [statements ret env]}]
  (let [context (:context env)]
    (when (and statements (= :expr context)) (emits "func() " (go-type (:tag ret)) " {"))
    (when statements
      (emits statements))
    (emit ret)
    (when (and statements (= :expr context)) (emits "}()"))))

(defmethod emit* :try
  [{:keys [env try catch name finally]}]
  (let [context (:context env)
        ret-tag (when ((hash-set (:tag try) 'ignore) (:tag catch))
                  (:tag try))]
    (if (or name finally)
      (let [out (when name (gensym "return__"))]
        (when (= :return (:context env))
          (emits "return "))
        (emits "func() " (when-not (= :statement (:context env)) (str "(" out " " (go-type ret-tag) " )")) " {")
        (when finally
          (assert (not= :constant (:op finally)) "finally block cannot contain constant")
          (emitln "defer func() {" finally "}()"))
        (when name
          (binding [*go-return-name* (or out *go-return-name*)]
            (emitln "defer func() { if " (munge name) " := recover(); "
                    (munge name) " != nil {" catch "}}()")))
        (emits "{" try "}")
        (emits "}()"))
      (emits try))
    (when (= :statement context)
      (emitln ""))))

(defn emit-let
  [{:keys [bindings expr env tag]} is-loop]
  (let [context (:context env)
        loop-needs-interface? #(and is-loop (not (or (go-primitive %) ('#{goog.string/StringBuffer} %))))]
    (if (= :expr context)
      (emits "func() " (go-type (when-not (loop-needs-interface? tag)
                                  tag)) " {")
      (emits "{"))
    (binding [*lexical-renames* (into *lexical-renames*
                                      (when (= :statement context)
                                        (map #(vector (System/identityHashCode %)
                                                      (gensym (str (:name %) "-")))
                                             bindings)))
              *go-loop-vars* (into *go-loop-vars* (when is-loop
                                                    (map :name bindings)))]
      (doseq [{:keys [init] :as binding} bindings]
        (emitln "var " binding
                (when (or (untyped-nil-needs-type? init)
                          (loop-needs-interface? (:tag init)))
                  " interface{}")
                " = " init))  ; Binding will be treated as a var
      (assign-to-blank bindings)
      (when is-loop (emitln "for {"))
      (emits expr)
      (when is-loop
        (when (= :statement (:context env))
          (emitln "break"))
        (emitln "}")))
    (if (= :expr context)
      (emits "}()")
      (emitln "}"))))

(defmethod emit* :let [ast]
  (emit-let ast false))

(defmethod emit* :loop [ast]
  (emit-let ast true))

(defn go-try-to-ressurect-impl [{:keys [op tag init info]}]
  (cond
   (and (= tag 'cljs.core/IMap)
        (<= (count (:keys info)) array-map-threshold))
   'cljs.core/PersistentArrayMap

   (= '(. PersistentTreeMap -EMPTY) (:form init))
   'cljs.core/PersistentTreeMap

   (= 'seq tag)
   'cljs.core/ISeq

   (and (= 'not-native tag) (= op :var))
   (go-try-to-ressurect-impl (if (:op info) info init))

   :else
   ('{cljs.core/IList cljs.core/List
      cljs.core/ISet cljs.core/PersistentHashSet
      cljs.core/IVector cljs.core/PersistentVector
      cljs.core/IMap cljs.core/PersistentHashMap} tag tag)))

(defn go-unbox-no-emit-with-nil-check [tag x]
  (let [coercion (when-not ('#{goog js} (and (symbol? tag)
                                             (some-> tag namespace symbol)))
                   (go-unbox-no-emit tag x))
        type? (= \* (first (go-type tag)))
        nil-check? (and type? coercion)
        return (gensym "return__")]
    (str
     (when nil-check?
       (str "func() (" return " " (go-type tag) ") { " return ", _ = "))
     (emit-str x)
     coercion
     (when nil-check?
       "; return }()"))))

(defmethod emit* :recur
  [{:keys [frame exprs]}]
  (when-let [params (seq (:params frame))]
    (emitln (comma-sep params)
            " = "
            (comma-sep (for [[e p] (map vector exprs params)
                             :let [tag (go-primitive (:tag p))]]
                         (str (emit-str e) (go-unbox-no-emit tag e))))))
  (emitln "continue"))

(defmethod emit* :letfn
  [{:keys [bindings expr env tag]}]
  (let [context (:context env)]
    (if (= :expr context)
      (emits "func() " (go-type tag) " {")
      (emitln "{"))
    (emitln "var " (comma-sep (map munge bindings)) " *" (go-core "AFn"))
    (doseq [{:keys [init] :as binding} bindings]
      (emitln (munge binding) " = " init))
    (assign-to-blank bindings)
    (emits expr)
    (if (= :expr context)
      (emits "}()")
      (emitln "}"))))

(defmethod emit* :invoke
  [{:keys [f args env] :as expr}]
  (let [info (:info f)
        fn? (and ana/*cljs-static-fns*
                 ; (not (:dynamic info))
                 (or (:fn-var info)
                     (some->> info :name (ana/resolve-existing-var (dissoc env :locals)) :declared-var)
                     (-> info :init :info :fn-var)))
        protocol (or (:protocol info)
                     (and (= *go-protocol-fn* (:name info)) ;; this is a hack to deal with self-calls, to be revisited.
                          *go-protocol*))
        object? (= 'cljs.core/Object protocol)
        protocol (when (not object?)
                   protocol)
        tag      (:tag (first (:args expr)))
        proto? (and protocol tag
                 (or (and ana/*cljs-static-fns* protocol (= tag 'not-native))
                     (and
                       (or ana/*cljs-static-fns*
                           (:cljs.analyzer/protocol-inline env))
                       (or (= protocol tag)
                           ;; ignore new type hints for now - David
                           (and (not (set? tag))
                                (not ('#{any clj clj-or-nil} tag))
                                (when-let [ps (:protocols (ana/resolve-existing-var (dissoc env :locals) tag))]
                                  (ps protocol)))))))
        opt-not? (and (= (:name info) 'cljs.core/not)
                      (= (:tag (first (:args expr))) 'boolean))
        ns (:ns info)
        go? (and ns (not (ana/get-namespace ns))
                 (some #(get (% (ana/get-namespace ana/*cljs-ns*)) ns) [:requires :uses]))
        js? ('#{js Math} ns)
        goog? (go-goog? ns)
        native? (or js? goog? go? object?)
        keyword? (and (= (-> f :op) :constant)
                      (keyword? (-> f :form)))
        arity (count args)
        [params] ((group-by count (:method-params info)) arity)
        mfa (:max-fixed-arity info)
        variadic-invoke (and (:variadic info) (or (> arity mfa) (= 1 (count (:method-params info)))))
        primitive-sig (go-type-suffix params (-> f :info :ret-tag))
        has-primitives? (not (or (re-find #"^I+$" primitive-sig) variadic-invoke))
        tags-match? true ; (= (map :tag params) (map :tag args))
        ifn? (when (symbol? (:tag info))
               ('cljs.core/IFn (some->> (:tag info) (ana/resolve-existing-var (dissoc env :locals)) :protocols)))
        coerce? (and (or (:field info) (:binding-form? info) (#{:invoke :var :let} (:op f)))
                     (not (or fn? (= 'function (:tag info)) ifn?)))
        static-field-receiver? (-> expr :args first :target :info :type)
        ret-tag (:tag expr)
        real-ret-tag (when (go-needs-coercion? ret-tag *go-return-tag*)
                       *go-return-tag*)
        unbox? (not (or has-primitives? (= :statement (:context env))))
        unbox-fn (some '{seq Seq_ boolean Truth_} [real-ret-tag ret-tag])]
    (binding [*go-return-tag* (when-not unbox-fn
                                real-ret-tag)]
      (emit-wrap env
        (when (and unbox? unbox-fn)
          (emits (go-core unbox-fn) "("))
        (cond
         opt-not?
         (emits "!(" (first args) ")")

         protocol ;; needs to take the imported name of protocols into account, very lenient now, assumes any type implements it.
         (let [pimpl (str (-> info :name name munge go-public) "_Arity" (count args))
               receiver (first args)
               v (when-let [v (go-try-to-ressurect-impl receiver)]
                   (when (symbol? v)
                     (ana/resolve-existing-var (dissoc env :locals) v)))]
           (emits (if (or (*go-loop-vars* (-> receiver :info :name))
                          (and (or (= (go-type tag) "interface{}")
                                   (go-primitive tag)
                                   (and (:protocol-symbol v)
                                        (not= (:name v) protocol)))
                               (not (get (:protocols v) protocol))
                               (not static-field-receiver?)))
                    (str (go-core "Decorate_") "(" (emit-str receiver) ").(" (go-type protocol) ")")
                    (first args) )
                  "." pimpl "(" (comma-sep (rest args)) ")"))

         keyword?
         (emits f ".X_invoke_Arity" arity "(" (comma-sep args) ")")

         variadic-invoke
         (emits f ".X_invoke_ArityVariadic(" (comma-sep (take mfa args))
                (when (pos? mfa) ",")
                (go-core "Array_seq") ".X_invoke_Arity1([]interface{}{" (comma-sep (drop mfa args)) "}))")

         native?
         (do
           (warn-on-reflection expr)
           (emits (go-core "Native_invoke_func") ".X_invoke_Arity2(" f ","
                  "[]interface{}{" (comma-sep args) "})"))

         (and has-primitives? tags-match?)
         (emits f ".Arity" arity primitive-sig "(" (comma-sep args) ")")

         :else
         (emits f (when coerce? (str ".(" (go-core "CljsCoreIFn") ")")) ".X_invoke_Arity" arity "(" (comma-sep args) ")"))

        ;; this is somewhat optimistic, the analyzer tags the expression based on the body of the fn, not the actual return type.
        (when unbox?
          (if unbox-fn
            (emits ")")
            (emits (go-unbox-no-emit ret-tag nil))))))))

(defn normalize-goog-ctor [ctor]
  (let [type (go-normalize-goog-type (-> ctor :info :name str))]
    (-> ctor
        (assoc-in [:info :name] type)
        (assoc-in [:info :ns] (symbol (namespace type))))))

(defmethod emit* :new
  [{:keys [ctor args env tag]}]
  (let [record? ('cljs.core/IRecord (:protocols (ana/resolve-existing-var (dissoc env :locals) tag)))
        fields (go-fields-of-type (-> ctor :info :name))]
    (binding [*go-return-tag* nil
              *go-dot* true]
      (emit-wrap env
        (emits "(&" (if (= 'goog (-> ctor :info :ns))
                      (normalize-goog-ctor ctor)
                      ctor) "{"
                      (comma-sep
                       (concat
                        (if-let [types (seq (map (comp :tag meta) fields))]
                          (map go-unbox (concat types (when record? (repeat nil))) args)
                          args)
                        (when record?
                          (repeat (- (+ (count fields) 3) (count args)) "nil"))))
                      "})"
                      )))))

(defmethod emit* :set!
  [{:keys [target val env form] :as ast}]
  (when-not (go-skip-set! form)
    (emit-wrap env
      (let [return (when (#{:expr :return} (:context env))
                     (gensym "return__"))
            tag-of-target (go-tag-of-target target)
            val (if (and (= 'boolean tag-of-target)
                         (go-needs-coercion? (:tag val) tag-of-target))
                  (str (go-core "Truth_") "(" (emit-str val) ")")
                  (go-unbox tag-of-target val))]
        (when return
          (emits "func() " (go-type tag-of-target) " {")
          (emitln "var " return " = " val))
        (let [val (or return val)
              static? (go-static-field? target)]
          (if-let [reflective-field (and (= "interface{}" (-> target :target :tag go-type))
                                         (not static?)
                                         (:field target))]
            (do
              (warn-on-reflection target)
              (emits (go-core "Native_set_instance_field") ".X_invoke_Arity3(" (:target target) ","
                      (wrap-in-double-quotes (munge (go-public reflective-field) #{}))
                      ","  val ")")
              (when-not (= :statement (:context env))
                (emitln (go-unbox-no-emit (:tag val) nil))))
            (if (and static? (not *go-def-vars*))
              (swap! *go-defs* conj ast)
              (do (when (and static? (= :statement (:context env)))
                    (emits "var "))
                  (emitln target " = " val))))
          (when return
            (emitln " return " return)
            (emits "}()")))))))

(def js-base-libs '#{js js.Math goog})

(defmethod emit* :ns
  [{:keys [name requires uses imports require-macros env]}]
  (emitln "// " name)
  (emitln)
  (emit-comment (-> name meta :doc)
                (some->> name meta :author (str "Author: ") vector))
  (emitln "package " (last (string/split (str (munge name)) #"\.")))
  (emitln)
  (emitln "import (")
  (doseq [lib (distinct (concat (when-not (= name 'cljs.core)
                                  '[cljs.core])
                                js-base-libs
                                (map #(symbol (string/replace % #"(.+)(\..+)$" "$1")) (vals imports))
                                (vals (apply dissoc requires (keys imports))) (vals uses)))]
    (emitln "\t" (when-not (js-base-libs lib)
                   (str (string/replace (munge lib) "." "_") " "))
            (wrap-in-double-quotes
             (str (*go-import-prefix* lib)
                  (-> lib
                      (string/replace #"[._]" "/")
                      (string/replace #"[-]" "_"))))))
  (emitln ")")
  (emitln))

(defn typed-fields [fields]
  (for [field fields]
    (str (go-public (munge field)) " " (-> field meta :tag go-type))))

(defmethod emit* :deftype*
  [{:keys [t fields pmasks body] :as ast}]
  (if *go-def-vars*
    (do
      (emitln "type " (-> t go-type-fqn munge) " struct { " (interpose "\n" (typed-fields fields)) " }")
      (emit body))
    (swap! *go-defs* conj ast)))

(defmethod emit* :defrecord*
  [{:keys [t fields pmasks body] :as ast}]
  (if *go-def-vars*
    (let [fields (map (comp go-public munge) fields)]
      (emitln "type " (-> t go-type-fqn munge) " struct { " (interpose "\n" (typed-fields fields))
              "\nX__meta interface{}\nX__extmap interface{}\nX__hash interface{} }")
      (emit body))
    (swap! *go-defs* conj ast)))

(defmethod emit* :dot
  [{:keys [target field method args env] :as dot}]
  (let [tag (:tag target)
        static? (go-static-field? dot)
        decorator (go-native-decorator tag)
        assume-array? (and (= (go-type tag) "interface{}")
                           ('#{push pop splice slice} method))
        decorator (or decorator (when assume-array?
                                  (go-native-decorator 'array)))
        reflection? (or (*go-loop-vars* (-> target :info :name))
                        (and (= "interface{}" (go-type tag)) (not static?) (not decorator)))]
    (binding [*go-return-tag* (when (and (not static?)
                                         (go-needs-coercion? tag *go-return-tag*))
                                *go-return-tag*)
              *go-dot* true]
      (emit-wrap env
        (if reflection?
          (do
            (warn-on-reflection dot)
            (if field
              (emits (go-core "Native_get_instance_field") ".X_invoke_Arity2(" target ","
                     (wrap-in-double-quotes (munge (go-public field) #{})) ")")
              (emits (go-core "Native_invoke_instance_method") ".X_invoke_Arity3(" target ","
                     (wrap-in-double-quotes (munge (go-public method) #{})) ","
                     "[]interface{}{" (comma-sep args) "})"))
            (when-not (or (= :statement (:context env)) static?)
              (emits (go-unbox-no-emit (:tag dot) nil))))
          (do
            (emits (cond
                    (fn? decorator) (decorator target)
                    (symbol? decorator) (str decorator "(" (emit-str target) ")")
                    :else target)
                   (if static? "_" "."))
            (if field
              (emits (munge (go-public field) #{}))
              (emits (munge (go-public method) #{})
                     (when static?
                       (str ".X_invoke_Arity" (count args)))
                     "("
                     (comma-sep args)
                     ")"))))))))

(defmethod emit* :js
  [{:keys [env code js-op segs args numeric tag form] :as ast}]
  (if (and (-> form meta :top-level) (not *go-def-vars*))
    (swap! *go-defs* conj ast)
    (let [aset-return? (and (= 'cljs.core/aset js-op) (#{:expr :return} (:context env)))
          box? (= 'removed-leaf? (-> args first :info :name)) ;; horrific hack to cancel out another.
          [segs args] (if box?
                        [segs args]
                        (case js-op
                          cljs.core/make-array [segs [(go-unbox 'number (first args))]]
                          cljs.core/alength (if ('#{string array} (-> args first :tag))
                                              [segs args]
                                              [[(str (go-core "Alength_") "(") ")"] args])
                          cljs.core/aget (if (or ('#{string array} (-> args first :tag))
                                                 (> (count args) 2))
                                           [segs (cons (go-unbox 'array (first args)) (map (partial go-unbox 'number) (rest args)))]
                                           [[(str (go-core "Aget_") "(") "," ")"]
                                            [(first args) (go-unbox 'number (second args))]])
                          cljs.core/aset [segs (concat [(go-unbox 'array (first args))]
                                                       (map (partial go-unbox 'number) (butlast (rest args)))
                                                       [(last args)])]
                          [segs args]))]
      (binding [*go-return-tag* (when (go-needs-coercion? tag *go-return-tag*)
                                  *go-return-tag*)]
        (emit-wrap env
                   (when aset-return?
                     (emits "func() " (go-type (:tag (last args))) "{ "))
                   (cond
                    code (emits code)
                    box? (emits (go-unbox 'cljs.core/Box (first args))
                                ".Val" (when (= 'cljs.core/aset js-op)
                                         (str " = " (emit-str (second args)))))
                    :else (emits (interleave (concat segs (repeat nil))
                                             (map (if numeric (partial go-unbox 'number) identity)
                                                  (concat args [nil])))))
                   (when aset-return?
                     (emits "; return " (first args) (map #(str "[int(" % ")]") (butlast (rest args)))
                            (go-unbox-no-emit (:tag (last args)) nil) " }()")))))))

(defn rename-to-js
  "Change the file extension from .cljs to .js. Takes a File or a
  String. Always returns a String."
  [file-str]
  (clojure.string/replace file-str #"\.cljs$" ".go"))

(defn mkdirs
  "Create all parent directories for the passed file."
  [^File f]
  (.mkdirs (.getParentFile (.getCanonicalFile f))))

(defn ensure-ns-exist
  ([env] (ensure-ns-exist ana/*cljs-ns* env))
  ([ns env]
     (when-not (ana/get-namespace ns)
       (ana/analyze env (list 'ns ns)))
     (assoc env :ns (ana/get-namespace ns))))

(defn setup-native-defs [env]
  (binding [ana/*cljs-ns* 'cljs.core]
    (ana/analyze (ensure-ns-exist env)
                 '(defprotocol Object
                    (^string toString [this])
                    (^boolean equiv [this other])))))

(defn with-core-cljs
  "Ensure that core.cljs has been loaded."
  ([] (with-core-cljs nil))
  ([opts] (with-core-cljs opts (fn [])))
  ([opts body]
     (do
       (when-not (get-in @env/*compiler* [::ana/namespaces 'cljs.core :defs])
         (setup-native-defs (ana/empty-env))
         (ana/analyze-file "cljs/core.cljs" opts)
         (ana/analyze-file "cljs/core/overrides.cljs"))
       (body))))

(defn url-path [^File f]
  (.getPath (.toURL (.toURI f))))

(def main-src "// +build ignore

package main

import cljs_core `github.com/hraberg/cljs2go/cljs/core`
import main_ns `.`

func main() {
	cljs_core.X_STAR_main_cli_fn_STAR_ = main_ns.X_main
	cljs_core.Main_()
}
")

(defn compile-file*
  ([src dest] (compile-file* src dest nil))
  ([src dest opts]
    (env/ensure
      (with-core-cljs opts
        (fn []
          (with-open [out ^java.io.Writer (io/make-writer dest {})]
            (binding [*out* out
                      ana/*cljs-file* (.getPath ^File src)
                      ana/*cljs-ns* 'cljs.user
                      tags/*cljs-data-readers* (assoc tags/*cljs-data-readers* 'queue read-queue)
                      reader/*alias-map* (or reader/*alias-map* {})
                      *go-line-numbers* (boolean (:source-map opts))]
              (let [forms (ana/forms-seq src)
                    [ns-ast forms] (let [ns-ast (ana/analyze (ana/empty-env) (first forms) nil opts)]
                                     (if (= :ns (:op ns-ast))
                                       [ns-ast forms]
                                       [nil forms]))]
                (emitln "// Compiled by ClojureScript to Go " (clojurescript-to-go-version))
                (when ns-ast
                  (emitln ns-ast))
                (emitln "func init() {")
                (doseq [form forms
                        :let [ast (ana/analyze (ana/empty-env) form nil opts)]
                        :when (not= ns-ast ast)]
                  (emit ast))
                (binding [*go-def-vars* true
                          *go-assign-vars* false]
                  (emitln "}")
                  (loop [[ast & defs] @*go-defs* emitted-names #{}]
                    (when ast
                      (if (= :def (:op ast))
                        (let [def-name (:name ast)]
                          (when-not (emitted-names def-name)
                            (emitln ast))
                          (when (= "-main" (name def-name))
                            (spit (io/file (.getParentFile ^File dest) "main.go") main-src))
                          (recur defs (conj emitted-names def-name)))
                        (do
                          (emitln ast)
                          (recur defs emitted-names))))))))))))))

(defn compiled-by-version [^File f]
  (with-open [reader (io/reader f)]
    (let [match (->> reader line-seq first
                     (re-matches #".*ClojureScript to Go (.*)$"))]
      (and match (second match)))))

(defn requires-compilation?
  "Return true if the src file requires compilation."
  ([src dest] (requires-compilation? src dest nil))
  ([^File src ^File dest opts]
    (env/ensure
      (or (not (.exists dest))
          (some #(> (.lastModified ^File %) (.lastModified dest))
                (cons src (when (.exists (io/file "cljs/core/rt.go"))
                            (cons (io/file "cljs/core/rt.go") (file-seq (io/file "src"))))))
          (let [version' (compiled-by-version dest)
                version  (clojurescript-to-go-version)]
            (and version (not= version version')))))))

(defn compile-file
  "Compiles src to a file of the same name, but with a .js extension,
   in the src file's directory.

   With dest argument, write file to provided location. If the dest
   argument is a file outside the source tree, missing parent
   directories will be created. The src file will only be compiled if
   the dest file has an older modification time.

   Both src and dest may be either a String or a File.

   Returns a map containing {:ns .. :provides .. :requires .. :file ..}.
   If the file was not compiled returns only {:file ...}"
  ([src]
    (let [dest (rename-to-js src)]
      (compile-file src dest nil)))
  ([src dest]
    (compile-file src dest nil))
  ([src dest opts]
    (let [src-file (io/file src)
          dest-file (io/file dest)]
      (if (.exists src-file)
        (try
          (let [{:keys [ns] :as ns-info} (ana/parse-ns src-file dest-file opts)]
            (if (requires-compilation? src-file dest-file opts)
              (do (mkdirs dest-file)
                  (when (and (contains? (::ana/namespaces @env/*compiler*) ns)
                             (not (:overrides? opts)))
                  (swap! env/*compiler* update-in [::ana/namespaces] dissoc ns))
                  (compile-file* src-file dest-file opts)
                ns-info)
              (do
                (when-not (contains? (::ana/namespaces @env/*compiler*) ns)
                  (with-core-cljs opts (fn [] (ana/analyze-file src-file opts)))))))
          (catch Exception e
            (throw (ex-info (str "failed compiling file:" src) {:file src} e))))
        (throw (java.io.FileNotFoundException. (str "The file " src " does not exist.")))))))


(defn relative-path-parts [cljs-file]
  (string/split
   (util/munge-path
    (str (:ns (ana/parse-ns cljs-file)))) #"\."))

(defn cljs-files-in
  "Return a sequence of all .cljs files in the given directory."
  [dir]
  (filter #(let [name (.getName ^File %)]
             (and (.endsWith name ".cljs")
                  (not= \. (first name))
                  (not (contains? cljs-reserved-file-names name))))
          (file-seq dir)))

(defn ^File to-go-target-file
  [target cljs-file]
  (let [relative-path (relative-path-parts cljs-file)
        parents (butlast relative-path)]
    (io/file
     (io/file (util/to-path (concat (cons target parents) [(last relative-path)])))
     (str (last relative-path) ".go"))))

(defn compile-root
  "Looks recursively in src-dir for .cljs files and compiles them to
   .js files. If target-dir is provided, output will go into this
   directory mirroring the source directory structure. Returns a list
   of maps containing information about each file which was compiled
   in dependency order."
  ([src-dir]
     (compile-root src-dir "out"))
  ([src-dir target-dir]
     (compile-root src-dir target-dir nil))
  ([src-dir target-dir opts]
     (swap! env/*compiler* assoc :root src-dir)
     (let [src-dir-file (io/file src-dir)]
       (loop [cljs-files (cljs-files-in src-dir-file)
              output-files []]
         (if (seq cljs-files)
           (let [cljs-file (first cljs-files)
                 output-file (to-go-target-file target-dir cljs-file)
                 ns-info (compile-file cljs-file output-file opts)]
             (recur (rest cljs-files) (conj output-files (assoc ns-info :file-name (.getPath output-file)))))
           output-files)))))
