/* Black Box — P3 "Signal Stage" query bar — T5/T6: KQL-lite tokenizer + token-coloring overlay
   + cursor-context suggestion popover.
   A tuned signal-input instrument over the existing search field: a transparent-text native
   <input name="query"> rides above an aligned <pre> overlay whose colored <span> tokens light up
   per KQL-lite type, with a red wavy underline on genuinely-invalid structure (unbalanced parens)
   and a dim/incomplete treatment for still-typing states (an unterminated quote opens to end-of-
   input). Free-text submit is byte-for-byte unchanged: the input keeps name="query" and is never
   re-created — we only addEventListener and append a sibling overlay.

   T6 adds a cursor-aware suggestion popover that resolves, by caret context:
     • at a field position   -> field names from options.fields (or /api/search/fields)
     • after `field:`         -> VALUES fetched from /api/search/values?field=&prefix=&limit=,
                                 debounced ~120ms on an input/keyup channel that is independent
                                 of form submit (n=1-6,24), cancelled per-keystroke via an
                                 AbortController + a request-generation guard (n=10-12).
     • after a complete clause-> operators AND / OR / NOT.
   The popover is an ARIA combobox: DOM focus stays on the input, the active row is tracked via
   aria-activedescendant (n=7). Enter SELECTS the highlighted suggestion and preventDefault()s
   ONLY when the popover is open AND a row is highlighted; otherwise Enter falls through to native
   form submission untouched (n=7,8,9) — never an unconditional preventDefault on Enter. The
   pending debounce timer + any in-flight fetch are cleared on submit (n=11). Value-fetch errors
   or empty results fail silent: the popover simply shows nothing (n=12).

   Classic script (IIFE) — exposes window.BlackBoxQueryBar:
     attach(form, options)  -> resolve the named input, own a mirror overlay, color tokens,
                               red-underline unbalanced structure, and (when a popover element is
                               supplied) the cursor-context suggestion layer.
     tokenize(input)        -> hand-rolled KQL-lite lexer; tokens carry {type,start,end,value}.
     parse(tokens|string)   -> forgiving precedence-climbing parser (NOT > AND > OR, parens
                               override). Never throws; returns { ast, errors }.
     mount(opts)            -> backward-compat adapter for the existing wiring in app.js.

   No deps, no build. Loaded before app.js; matches the IIFE/global style of graph.js. */
(function () {
  "use strict";

  var OP_SET = { AND: 1, OR: 1, NOT: 1 };
  var RANGE_OPS = { ">": 1, "<": 1, ">=": 1, "<=": 1 };
  var CONJ = ["AND", "OR", "NOT"]; // operators suggested after a complete clause

  // Suggestion popover tuning. The 120ms debounce is a design parameter (n=16): it keeps the
  // value lookup off the hot per-keystroke path but does not gate input delay or form submit.
  var VALUE_DEBOUNCE_MS = 120;
  var SUGGEST_LIMIT = 8; // rows shown at once
  var VALUE_FETCH_LIMIT = 20; // /api/search/values?limit= — clamped server-side to [1,50]
  var FIELDS_URL = "/api/search/fields";
  var VALUES_URL = "/api/search/values";

  var reduceMotion = window.matchMedia
    ? window.matchMedia("(prefers-reduced-motion: reduce)").matches
    : false;

  // ---------------------------------------------------------------------------
  // Tokenizer (KQL-lite). Single forward pass producing a token for every char of
  // the input (whitespace included) so the overlay can rebuild the string exactly.
  // Each token carries explicit {type, start, end, value} offsets (n=58 liqe pattern)
  // to drive coloring + the red error underline. Types:
  //   field      bareword immediately followed by ':'  (e.g. source in source:claude)
  //   colon      the ':' separating field and value
  //   operator   AND / OR / NOT (case-insensitive)  OR  a range op  > >= < <=
  //   string     "double quoted phrase"  (error:true when unterminated -> incomplete)
  //   wildcard   a bareword containing '*', or a lone '*'  (field:* exists, val*)
  //   value      a bareword consumed as the value after 'field:'
  //   paren      ( or )
  //   text       free-text bareword or stray glyph
  //   ws         a run of whitespace
  // It never throws: every byte is consumed, unknown glyphs fall through to `text`.
  function isWordChar(c) {
    return /[A-Za-z0-9_.\-/@]/.test(c);
  }

  function tokenize(input) {
    input = input == null ? "" : String(input);
    var tokens = [];
    var i = 0;
    var n = input.length;
    var afterColon = false; // the next bareword is a value, not free text

    while (i < n) {
      var c = input[i];

      // whitespace run
      if (/\s/.test(c)) {
        var ws = i;
        while (i < n && /\s/.test(input[i])) i++;
        tokens.push({ type: "ws", start: ws, end: i, value: input.slice(ws, i) });
        afterColon = false;
        continue;
      }

      // quoted string — an unterminated quote opens the string to end-of-input and is
      // flagged incomplete (dim), never thrown (n=47,57). Inside-quote chars are literal.
      if (c === '"') {
        var qs = i;
        i++;
        while (i < n && input[i] !== '"') {
          if (input[i] === "\\" && i + 1 < n) i++; // skip the escaped char
          i++;
        }
        var terminated = i < n && input[i] === '"';
        if (terminated) i++;
        tokens.push({
          type: "string",
          start: qs,
          end: i,
          value: input.slice(qs, i),
          error: !terminated, // incomplete-but-valid; surfaced as dim, not red
          incomplete: !terminated,
        });
        afterColon = false;
        continue;
      }

      // parens (sole grouping/precedence-override mechanism)
      if (c === "(" || c === ")") {
        tokens.push({ type: "paren", start: i, end: i + 1, value: c });
        i++;
        afterColon = false;
        continue;
      }

      // range / comparison operators  > >= < <=
      if (c === ">" || c === "<") {
        var op = c;
        if (input[i + 1] === "=") op += "=";
        tokens.push({ type: "operator", op: "range", start: i, end: i + op.length, value: op });
        i += op.length;
        afterColon = false;
        continue;
      }

      // colon — field/value separator. Only meaningful right after a bareword field.
      if (c === ":") {
        tokens.push({ type: "colon", start: i, end: i + 1, value: ":" });
        i++;
        afterColon = true;
        continue;
      }

      // lone wildcard (e.g. the '*' in 'field:*' after the colon is consumed below)
      if (c === "*") {
        tokens.push({ type: "wildcard", start: i, end: i + 1, value: "*" });
        i++;
        afterColon = false;
        continue;
      }

      // bareword: field (followed by ':'), boolean op, value (after ':'), or free text
      if (isWordChar(c)) {
        var ws2 = i;
        var hasWildcard = false;
        while (i < n && (isWordChar(input[i]) || input[i] === "*")) {
          if (input[i] === "*") hasWildcard = true;
          i++;
        }
        var word = input.slice(ws2, i);
        var follows = input[i]; // char immediately after the word
        if (follows === ":" && !afterColon) {
          tokens.push({ type: "field", start: ws2, end: i, value: word });
        } else if (OP_SET[word.toUpperCase()] && !afterColon) {
          tokens.push({ type: "operator", op: word.toUpperCase(), start: ws2, end: i, value: word });
        } else if (afterColon) {
          tokens.push({
            type: hasWildcard ? "wildcard" : "value",
            start: ws2,
            end: i,
            value: word,
          });
          afterColon = false;
        } else {
          tokens.push({
            type: hasWildcard ? "wildcard" : "text",
            start: ws2,
            end: i,
            value: word,
          });
        }
        continue;
      }

      // anything else — a stray glyph; keep it as text so the overlay stays char-exact
      tokens.push({ type: "text", start: i, end: i + 1, value: c });
      i++;
      afterColon = false;
    }
    return tokens;
  }

  // ---------------------------------------------------------------------------
  // Forgiving precedence-climbing parser. Deterministic KQL precedence NOT > AND > OR,
  // parens the only override (n=31,53,55,56 — NOT Lucene's left-to-right operator flag).
  // It is resilient, never recovery-fragile: it never throws, consumes >=1 token per
  // loop iteration (anti-hang, n=57), wraps genuinely-bad structure in error nodes, and
  // always returns a tree covering the tokens plus an `errors` list of {start,end,message}.
  function parse(source) {
    var tokens = Array.isArray(source) ? source : tokenize(source);
    // meaningful tokens only (drop ws) but keep offsets for error spans
    var toks = tokens.filter(function (t) {
      return t.type !== "ws";
    });
    var pos = 0;
    var errors = [];
    var fuel = toks.length * 4 + 16; // dev-time anti-hang budget (n=57)
    var depth = 0; // recursion-depth guard so pathological '(((((' / chained NOT can't
    var MAX_DEPTH = 512; // overflow the JS call stack — the parse must never throw (n=57).

    function peek() {
      return toks[pos] || null;
    }
    function next() {
      return toks[pos++] || null;
    }
    function isOp(t, name) {
      return t && t.type === "operator" && t.op === name;
    }
    // does this token begin a fresh term/primary? (used to fold whitespace-separated
    // bare terms into free text rather than erroring on them — plan-4 "bare terms =
    // free text"; KQL forbids implicit-AND (n=32) so the join is a non-boolean
    // concatenation, not an `and` node, and the original spans/precedence are preserved)
    function startsTerm(t) {
      if (!t) return false;
      if (t.type === "field" || t.type === "value" || t.type === "wildcard" || t.type === "string" || t.type === "text") {
        return true;
      }
      if (t.type === "paren" && t.value === "(") return true;
      if (isOp(t, "NOT")) return true;
      return false;
    }
    function err(tok, message) {
      var span = tok || toks[toks.length - 1] || { start: 0, end: 0 };
      errors.push({ start: span.start, end: span.end, message: message });
    }

    // OR (loosest): AndQuery (OR AndQuery)*
    function parseOr() {
      var left = parseAnd();
      while (isOp(peek(), "OR")) {
        if (fuel-- <= 0) break;
        next();
        var right = parseAnd();
        left = { type: "or", left: left, right: right };
      }
      return left;
    }

    // AND: NotQuery (AND NotQuery)*  — and the implicit-join slot. KQL has no implicit
    // boolean AND between adjacent barewords (n=32), so whitespace-separated terms with no
    // explicit operator are folded into a single field-less free-text `concat` node (plan-4:
    // bare terms = free text) instead of being flagged as `Unexpected` errors — keeping the
    // parse forgiving (only unbalanced parens are genuinely invalid -> red). The explicit
    // AND keyword still binds tighter than OR and looser than NOT.
    function parseAnd() {
      var left = parseNot();
      for (;;) {
        if (fuel-- <= 0) break;
        if (isOp(peek(), "AND")) {
          next();
          left = { type: "and", left: left, right: parseNot() };
          continue;
        }
        // adjacency with no operator: a bare term sitting next to another -> free text.
        if (startsTerm(peek())) {
          var rt = parseNot();
          left = {
            type: "concat",
            left: left,
            right: rt,
            start: left && left.start != null ? left.start : rt && rt.start,
            end: rt && rt.end != null ? rt.end : left && left.end,
          };
          continue;
        }
        break;
      }
      return left;
    }

    // NOT (tightest unary prefix)
    function parseNot() {
      if (isOp(peek(), "NOT") && depth < MAX_DEPTH) {
        if (fuel-- > 0) {
          next();
          depth++;
          var operand = parseNot();
          depth--;
          return { type: "not", operand: operand };
        }
      }
      return parsePrimary();
    }

    function parsePrimary() {
      var t = peek();
      if (!t) {
        // ran out of tokens where a term was expected — trailing operator etc.
        var last = toks[toks.length - 1];
        if (last && last.type === "operator") err(last, "Expected a term after '" + last.value + "'");
        return { type: "empty", start: 0, end: 0 };
      }

      // depth backstop: a degenerate '(((((' / chained-NOT input recurses through the
      // OR->AND->NOT->PRIMARY cascade; cap it so the parse never overflows the call stack
      // and throws (n=57 anti-hang). Consume the token (guarantees progress) and bail out
      // of the descent with an error node — treated as genuinely-invalid -> red underline.
      if (depth >= MAX_DEPTH) {
        next();
        err(t, "Query nested too deeply");
        return { type: "error", token: t, start: t.start, end: t.end, error: true };
      }

      // parenthesised subquery — re-enter at the top (parens reset precedence, n=34)
      if (t.type === "paren" && t.value === "(") {
        var open = next();
        depth++;
        var inner = parseOr();
        depth--;
        var close = peek();
        if (close && close.type === "paren" && close.value === ")") {
          next();
          return { type: "group", node: inner, start: open.start, end: close.end };
        }
        // unbalanced '(' — genuinely invalid -> red underline (n=43,47,57)
        err(open, "Unbalanced '(' — missing ')'");
        return { type: "group", node: inner, start: open.start, end: (inner && inner.end) || open.end, error: true };
      }

      // stray ')' with no matching '(' — genuinely invalid
      if (t.type === "paren" && t.value === ")") {
        next();
        err(t, "Unbalanced ')' — no matching '('");
        return { type: "error", token: t, start: t.start, end: t.end, error: true };
      }

      // field clause:  field : value | "string" | * | wildcard   OR   field range value
      if (t.type === "field") {
        var field = next();
        var sep = peek();
        if (sep && sep.type === "colon") {
          next();
          var val = peek();
          if (val && (val.type === "value" || val.type === "string" || val.type === "wildcard")) {
            next();
            return {
              type: val.type === "wildcard" ? "wildcard" : val.type === "string" ? "phrase" : "term",
              field: field.value,
              value: val.value,
              start: field.start,
              end: val.end,
              incomplete: val.incomplete === true,
            };
          }
          // 'field:' with nothing after — incomplete-but-valid (still typing, n=48); dim, not red
          return {
            type: "term",
            field: field.value,
            value: "",
            start: field.start,
            end: sep.end,
            incomplete: true,
          };
        }
        if (sep && isOp(sep, "range")) {
          next();
          var rval = peek();
          if (rval && (rval.type === "value" || rval.type === "string" || rval.type === "wildcard")) {
            next();
            return {
              type: "range",
              field: field.value,
              op: sep.value,
              value: rval.value,
              start: field.start,
              end: rval.end,
            };
          }
          return { type: "range", field: field.value, op: sep.value, value: "", start: field.start, end: sep.end, incomplete: true };
        }
        // bare field with no colon — treat as free text term
        return { type: "term", field: null, value: field.value, start: field.start, end: field.end };
      }

      // a value/wildcard/string/text standing alone -> free-text term (field-less),
      // OR the left side of the space-separated KQL range form `field > value` (n=51:
      // the canonical KQL range is `field RangeOperator value`, e.g. `bytes > 10000`,
      // where the field is a bareword not followed by a colon so it tokenizes as text).
      if (t.type === "value" || t.type === "wildcard" || t.type === "string" || t.type === "text") {
        var ahead = toks[pos + 1];
        if ((t.type === "text" || t.type === "value") && isOp(ahead, "range")) {
          var rfield = next(); // the bareword field
          var rop = next(); // the range operator
          var rv = peek();
          if (rv && (rv.type === "value" || rv.type === "string" || rv.type === "wildcard" || rv.type === "text")) {
            next();
            return {
              type: "range",
              field: rfield.value,
              op: rop.value,
              value: rv.value,
              start: rfield.start,
              end: rv.end,
            };
          }
          // `field >` with nothing after — incomplete-but-valid (still typing); dim, not red.
          return {
            type: "range",
            field: rfield.value,
            op: rop.value,
            value: "",
            start: rfield.start,
            end: rop.end,
            incomplete: true,
          };
        }
        next();
        return {
          type: t.type === "wildcard" ? "wildcard" : t.type === "string" ? "phrase" : "term",
          field: null,
          value: t.value,
          start: t.start,
          end: t.end,
          incomplete: t.incomplete === true,
        };
      }

      // a leading colon / range op / stray operator with no left operand — skip one token
      // (consume >=1 to guarantee progress) and record an error node.
      next();
      err(t, "Unexpected '" + t.value + "'");
      return { type: "error", token: t, start: t.start, end: t.end, error: true };
    }

    var ast = parseOr();
    // anything left over (e.g. a dangling close paren run) — drain it as errors, but
    // always consume to avoid a hang.
    while (pos < toks.length) {
      if (fuel-- <= 0) break;
      var leftover = next();
      if (leftover && leftover.type === "paren" && leftover.value === ")") {
        err(leftover, "Unbalanced ')' — no matching '('");
      } else if (leftover) {
        err(leftover, "Unexpected '" + leftover.value + "'");
      }
    }
    return { ast: ast, errors: errors, tokens: tokens };
  }

  // Whole-string structural balance check — the authoritative red-underline signal for the
  // overlay. Quote imbalance is treated as *incomplete* (dim, handled per-token via the
  // unterminated-string flag); only paren imbalance is *invalid* (red). Quotes inside an
  // open string suppress paren counting so 'a("' does not double-flag.
  function balance(input) {
    input = input == null ? "" : String(input);
    var depth = 0;
    var minDepth = 0;
    var inString = false;
    for (var k = 0; k < input.length; k++) {
      var ch = input[k];
      if (inString) {
        if (ch === "\\") {
          k++;
          continue;
        }
        if (ch === '"') inString = false;
        continue;
      }
      if (ch === '"') inString = true;
      else if (ch === "(") depth++;
      else if (ch === ")") {
        depth--;
        if (depth < minDepth) minDepth = depth;
      }
    }
    return {
      parens: depth !== 0 || minDepth < 0, // genuinely invalid -> red
      openQuote: inString, // unterminated -> incomplete (dim), per-token
    };
  }

  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  // Build the colored overlay HTML for a given string. Pure function of the input — no DOM
  // reads — so it can be called from anywhere without forcing layout (n=13-18).
  function renderTokensHtml(text) {
    var tokens = tokenize(text);
    var bal = balance(text);
    var html = "";
    for (var t = 0; t < tokens.length; t++) {
      var tok = tokens[t];
      if (tok.type === "ws") {
        html += escapeHtml(tok.value);
        continue;
      }
      var cls = "qb-tok qb-tok--" + tok.type;
      // red underline only for genuinely-invalid structure: an unbalanced paren glyph.
      if (tok.type === "paren" && bal.parens) cls += " qb-tok--error";
      // an unterminated quote is incomplete, not invalid -> dim, no red wavy underline.
      if (tok.type === "string" && tok.incomplete) cls += " qb-tok--incomplete";
      html += '<span class="' + cls + '">' + escapeHtml(tok.value) + "</span>";
    }
    // zero-width joiner keeps the <pre> from collapsing height when empty / trailing-space
    return html + "​";
  }

  // ---------------------------------------------------------------------------
  // Caret-context analysis (pure: value string + caret offset -> a suggestion intent). It only
  // tokenizes the text *up to the caret* (Kibana's @kuery-cursor sentinel idea, n=48): the token
  // that ends exactly at the caret is the one being typed. We never read DOM/caret geometry here,
  // so this forces no reflow (n=13-18). Returns one of:
  //   { kind: "field",    prefix }                 -> suggest field names matching `prefix`
  //   { kind: "value",    field, prefix }          -> suggest VALUES of `field` matching `prefix`
  //   { kind: "operator", prefix }                 -> suggest AND/OR/NOT after a complete clause
  //   null                                          -> no suggestion (popover stays closed)
  // `replaceStart` marks where the typed fragment begins, so accepting a row replaces just it.
  function caretContext(value, caret) {
    value = value == null ? "" : String(value);
    if (caret == null || caret < 0) caret = value.length;
    if (caret > value.length) caret = value.length;
    var head = value.slice(0, caret);
    var toks = tokenize(head);

    // index of the last meaningful (non-ws) token and whether a space sits right before caret
    var last = null;
    var prev = null; // meaningful token before `last`
    for (var i = 0; i < toks.length; i++) {
      if (toks[i].type === "ws") continue;
      prev = last;
      last = toks[i];
    }
    var endsWithWs = toks.length > 0 && toks[toks.length - 1].type === "ws";
    // is the last meaningful token touching the caret (still being typed)?
    var typing = last && last.end === caret && !endsWithWs;

    // --- value context: caret right after `field:` (optionally with a partial value word) ---
    // case A: still typing the value bareword/wildcard immediately after a colon.
    if (typing && prev && prev.type === "colon") {
      // walk back to the field token before the colon
      var fIdxA = indexOfFieldBeforeColon(toks, prev);
      if (fIdxA != null) {
        var raw = last.value;
        // a wildcard fragment (val*) — strip a trailing '*' so the server prefix is the stem.
        var pfx = raw.replace(/\*+$/, "");
        return { kind: "value", field: toks[fIdxA].value, prefix: pfx, replaceStart: last.start };
      }
    }
    // case B: caret sits immediately after the colon with no value yet (field:|).
    if (last && last.type === "colon" && last.end === caret && !endsWithWs) {
      var fIdxB = indexOfFieldBeforeColon(toks, last);
      if (fIdxB != null) {
        return { kind: "value", field: toks[fIdxB].value, prefix: "", replaceStart: caret };
      }
    }

    // --- field context: typing a bareword that is the start of a clause (not a value, not an
    // operator keyword) and is immediately followed by ':' in intent — suggest field names. ---
    if (typing && (last.type === "field" || last.type === "value" || last.type === "text" || last.type === "wildcard")) {
      // a bareword right after `field:` was handled above; here `prev` is not a colon.
      if (!prev || prev.type !== "colon") {
        var w = last.value;
        // don't shadow a partially-typed operator keyword (AND/OR/NOT) — let it pass to operator
        if (!isOperatorPrefix(w)) {
          return { kind: "field", prefix: w, replaceStart: last.start };
        }
      }
    }

    // --- operator context: a complete clause just ended and the caret is past a space, OR the
    // caret is typing an operator keyword fragment. Suggest AND/OR/NOT. ---
    if (endsWithWs && last && clauseIsComplete(last, prev)) {
      return { kind: "operator", prefix: "", replaceStart: caret };
    }
    if (typing && isOperatorPrefix(last.value) && (!prev || prev.type !== "colon")) {
      return { kind: "operator", prefix: last.value.toUpperCase(), replaceStart: last.start };
    }

    // empty input or unclassified caret — suggest field names from scratch
    if (head.length === 0 || endsWithWs) {
      return { kind: "field", prefix: "", replaceStart: caret };
    }
    return null;
  }

  // find the index of the `field` token that immediately precedes a given colon token
  function indexOfFieldBeforeColon(toks, colonTok) {
    var seenColon = false;
    for (var i = toks.length - 1; i >= 0; i--) {
      if (toks[i] === colonTok) {
        seenColon = true;
        continue;
      }
      if (!seenColon) continue;
      if (toks[i].type === "ws") continue;
      return toks[i].type === "field" ? i : null;
    }
    return null;
  }

  // is `w` a (possibly partial, case-insensitive) prefix of AND/OR/NOT?
  function isOperatorPrefix(w) {
    if (!w) return false;
    var u = w.toUpperCase();
    for (var i = 0; i < CONJ.length; i++) {
      if (CONJ[i].indexOf(u) === 0) return true;
    }
    return false;
  }

  // does the token `last` (with predecessor `prev`) close a complete clause that an operator
  // could legitimately follow? A value/wildcard/phrase, a closed paren, or a `field:value`.
  function clauseIsComplete(last, prev) {
    if (!last) return false;
    if (last.type === "value" || last.type === "wildcard" || last.type === "text") return true;
    if (last.type === "string" && !last.incomplete) return true;
    if (last.type === "paren" && last.value === ")") return true;
    return false;
  }

  // ---------------------------------------------------------------------------
  // Suggestion engine. Owns the popover (an existing element with role="listbox"), the value-
  // fetch debounce + AbortController, the generation guard, and keyboard navigation. DOM focus
  // never leaves the input — the active row is tracked purely via aria-activedescendant (n=7).
  //
  // Independence guarantees (n=1-6,24): we only addEventListener "input"/"keyup"/"keydown"/
  // "blur"/"click" on the input and popover. The value lookup is a setTimeout macrotask, so a
  // pending debounce can never delay/drop a submit; we attach NO submit handler; and the only
  // preventDefault is on Enter, gated strictly to "popover open AND a row highlighted" (n=7-9).
  function createSuggestions(input, pop, opts) {
    opts = opts || {};
    // Field catalogue: prefer the caller-provided list (app.js loads /api/search/fields), else
    // lazy-fetch it ourselves the first time the bar is focused so the bar works stand-alone.
    var fields = normalizeFields(opts.fields);
    var fieldsLoaded = fields.length > 0;
    var fieldsLoading = false;

    var idBase = "qbsug-" + Math.random().toString(36).slice(2, 8);
    var items = []; // current suggestion rows: [{ label, meta, insert, kind }]
    var active = -1; // highlighted row index, -1 = none
    var open = false;
    var ctx = null; // the caret context the current `items` were built for

    var debounceTimer = 0; // pending value-fetch debounce (n=11 cancel-on-submit)
    var abortCtl = null; // in-flight value fetch (n=10-12)
    var gen = 0; // request-generation guard — discard a render if gen != latestGen (n=11,12)
    var detached = false;

    // base API + value-fetch override seams (tests / non-default deployments)
    var fieldsUrl = opts.fieldsUrl || FIELDS_URL;
    var valuesUrl = opts.valuesUrl || VALUES_URL;
    var fetchValues = typeof opts.fetchValues === "function" ? opts.fetchValues : defaultFetchValues;
    var fetchFields = typeof opts.fetchFields === "function" ? opts.fetchFields : defaultFetchFields;

    function defaultFetchFields() {
      if (typeof window.fetch !== "function") return Promise.resolve([]);
      return window
        .fetch(fieldsUrl, { headers: { Accept: "application/json" } })
        .then(function (r) {
          return r.ok ? r.json() : [];
        })
        .then(normalizeFields);
    }

    // Returns a promise of string[]; honours an AbortSignal so a superseded request is cancelled.
    function defaultFetchValues(field, prefix, signal) {
      if (typeof window.fetch !== "function") return Promise.resolve([]);
      var url =
        valuesUrl +
        "?field=" +
        encodeURIComponent(field) +
        "&prefix=" +
        encodeURIComponent(prefix) +
        "&limit=" +
        VALUE_FETCH_LIMIT;
      return window
        .fetch(url, { headers: { Accept: "application/json" }, signal: signal })
        .then(function (r) {
          return r.ok ? r.json() : [];
        })
        .then(function (arr) {
          return Array.isArray(arr) ? arr : [];
        });
    }

    // ----- popover rendering (insertAdjacentHTML-free: we build rows via createElement so the
    // popover's own listeners survive and we never reparse foreign HTML, n=22) -----
    function clearPop() {
      while (pop.firstChild) pop.removeChild(pop.firstChild);
    }

    function hide() {
      if (debounceTimer) {
        clearTimeout(debounceTimer);
        debounceTimer = 0;
      }
      cancelInFlight();
      if (!open && pop.hidden) return;
      open = false;
      active = -1;
      items = [];
      ctx = null;
      pop.hidden = true;
      clearPop();
      input.setAttribute("aria-expanded", "false");
      input.removeAttribute("aria-activedescendant");
    }

    function cancelInFlight() {
      if (abortCtl) {
        try {
          abortCtl.abort();
        } catch (e) {
          /* ignore */
        }
        abortCtl = null;
      }
    }

    function render(rows, context) {
      items = rows || [];
      ctx = context || null;
      if (!items.length) {
        hide();
        return;
      }
      clearPop();
      for (var i = 0; i < items.length; i++) {
        var row = document.createElement("div");
        row.className = "qb-pop-row";
        row.id = idBase + "-" + i;
        row.setAttribute("role", "option");
        row.setAttribute("aria-selected", "false");
        var label = document.createElement("span");
        label.className = "qb-pop-label";
        label.textContent = items[i].label;
        row.appendChild(label);
        if (items[i].meta) {
          var meta = document.createElement("span");
          meta.className = "qb-pop-meta";
          meta.textContent = items[i].meta;
          row.appendChild(meta);
        }
        pop.appendChild(row);
      }
      open = true;
      active = -1;
      pop.hidden = false;
      input.setAttribute("aria-expanded", "true");
      input.removeAttribute("aria-activedescendant");
    }

    function highlight(index) {
      if (!open || !items.length) return;
      var rows = pop.children;
      if (active >= 0 && active < rows.length) rows[active].setAttribute("aria-selected", "false");
      active = index;
      if (active < 0) {
        input.removeAttribute("aria-activedescendant");
        return;
      }
      if (active >= rows.length) active = rows.length - 1;
      var row = rows[active];
      row.setAttribute("aria-selected", "true");
      input.setAttribute("aria-activedescendant", row.id);
      // keep the active row in view without moving DOM focus off the input
      if (typeof row.scrollIntoView === "function") {
        try {
          row.scrollIntoView({ block: "nearest" });
        } catch (e) {
          /* older browsers: scrollIntoView(false) */
          row.scrollIntoView(false);
        }
      }
    }

    function move(delta) {
      if (!open || !items.length) return;
      var nextIdx;
      if (active < 0) {
        nextIdx = delta > 0 ? 0 : items.length - 1;
      } else {
        nextIdx = (active + delta + items.length) % items.length;
      }
      highlight(nextIdx);
    }

    // Accept a row: splice its `insert` text in place of the typed fragment, keeping the caret at
    // the end of the insertion. We set input.value synchronously (the submit reads live FormData,
    // not a debounced copy, so this cannot stale the payload — n=5), then refresh the overlay.
    function accept(index) {
      if (!open || index < 0 || index >= items.length || !ctx) return false;
      var item = items[index];
      var value = input.value;
      var start = ctx.replaceStart != null ? ctx.replaceStart : value.length;
      var caret = currentCaret();
      if (caret < start) caret = value.length;
      var before = value.slice(0, start);
      var after = value.slice(caret);
      var insert = item.insert;
      var next = before + insert + after;
      input.value = next;
      var pos = (before + insert).length;
      try {
        input.setSelectionRange(pos, pos);
      } catch (e) {
        /* number inputs etc. don't support setSelectionRange */
      }
      hide();
      if (typeof opts.onAccept === "function") {
        try {
          opts.onAccept(next);
        } catch (e) {
          /* never let a hook break the bar */
        }
      }
      // re-evaluate context after the insertion (e.g. field name -> ":" -> value suggestions)
      schedule();
      return true;
    }

    function currentCaret() {
      var c = input.selectionStart;
      return typeof c === "number" ? c : input.value.length;
    }

    // ----- context resolution + value fetch (the debounced, abortable channel) -----
    // schedule() runs on every input/keyup. Field + operator suggestions are synchronous; only
    // VALUE suggestions hit the network, and only those are debounced + abortable.
    function schedule() {
      if (detached) return;
      ensureFields();
      var context = caretContext(input.value, currentCaret());
      if (!context) {
        hide();
        return;
      }
      if (context.kind === "field") {
        cancelInFlight();
        if (debounceTimer) {
          clearTimeout(debounceTimer);
          debounceTimer = 0;
        }
        render(fieldRows(context.prefix), context);
        return;
      }
      if (context.kind === "operator") {
        cancelInFlight();
        if (debounceTimer) {
          clearTimeout(debounceTimer);
          debounceTimer = 0;
        }
        render(operatorRows(context.prefix), context);
        return;
      }
      if (context.kind === "value") {
        scheduleValueFetch(context);
        return;
      }
      hide();
    }

    // Debounced + abortable value lookup. Each keystroke bumps `gen`, aborts the prior fetch, and
    // (re)arms a single ~120ms timer; the response render is guarded by both signal.aborted AND a
    // generation check so a late/out-of-order response can never clobber the popover (n=10-12).
    function scheduleValueFetch(context) {
      if (debounceTimer) clearTimeout(debounceTimer);
      cancelInFlight();
      var myGen = ++gen;
      debounceTimer = setTimeout(function () {
        debounceTimer = 0;
        if (detached || myGen !== gen) return;
        var ctl = typeof window.AbortController === "function" ? new window.AbortController() : null;
        abortCtl = ctl;
        var signal = ctl ? ctl.signal : undefined;
        Promise.resolve(fetchValues(context.field, context.prefix, signal))
          .then(function (vals) {
            // discard if superseded (generation guard) or this very request was aborted (n=12).
            if (detached || myGen !== gen) return;
            if (signal && signal.aborted) return;
            if (abortCtl === ctl) abortCtl = null;
            var rows = valueRows(vals, context);
            if (!rows.length) {
              // fail-silent: empty / unenumerable field -> popover shows nothing (n=12).
              hide();
              return;
            }
            render(rows, context);
          })
          .catch(function (e) {
            // Swallow AbortError so it never surfaces in the search-results pane (n=10-12). Any
            // other value-fetch error also fails silent: the popover simply shows nothing.
            if (abortCtl === ctl) abortCtl = null;
            if (detached || myGen !== gen) return;
            hide();
          });
      }, VALUE_DEBOUNCE_MS);
    }

    function ensureFields() {
      if (fieldsLoaded || fieldsLoading) return;
      fieldsLoading = true;
      Promise.resolve(fetchFields())
        .then(function (list) {
          fieldsLoaded = true;
          fieldsLoading = false;
          if (list && list.length) fields = list;
        })
        .catch(function () {
          fieldsLoading = false; // fail-silent; a later focus retries
        });
    }

    // ----- row builders -----
    function fieldRows(prefix) {
      var p = (prefix || "").toLowerCase();
      var out = [];
      for (var i = 0; i < fields.length && out.length < SUGGEST_LIMIT; i++) {
        var f = fields[i];
        if (p && f.name.toLowerCase().indexOf(p) !== 0) continue;
        out.push({ label: f.name, meta: f.type || "field", insert: f.name + ":", kind: "field" });
      }
      return out;
    }

    function operatorRows(prefix) {
      var p = (prefix || "").toUpperCase();
      var out = [];
      for (var i = 0; i < CONJ.length; i++) {
        if (p && CONJ[i].indexOf(p) !== 0) continue;
        out.push({ label: CONJ[i], meta: "operator", insert: CONJ[i] + " ", kind: "operator" });
      }
      return out;
    }

    function valueRows(vals, context) {
      var out = [];
      for (var i = 0; i < vals.length && out.length < SUGGEST_LIMIT; i++) {
        var v = String(vals[i]);
        out.push({ label: v, meta: context.field, insert: quoteValue(v), kind: "value" });
      }
      return out;
    }

    // wrap a value in double quotes only when it carries whitespace or a KQL special char so the
    // bare common case stays clean (source:claude) but `a b` / `a:b` become "a b" / "a:b".
    function quoteValue(v) {
      if (/[\s():<>"*{}\\]/.test(v)) {
        return '"' + v.replace(/\\/g, "\\\\").replace(/"/g, '\\"') + '"';
      }
      return v;
    }

    // ----- events -----
    // keydown: navigation + Enter selection. preventDefault is called ONLY for keys we consume,
    // and Enter is preventDefault'd ONLY when the popover is open AND a row is highlighted — so a
    // plain Enter (popover closed, or open with no highlight) falls through to native submit
    // untouched (n=7,8,9). Arrow keys move the highlight; Escape closes the popover.
    function onKeydown(e) {
      var key = e.key;
      if (!open) {
        // popover closed: only ArrowDown (re-)opens suggestions; everything else (incl. Enter)
        // is left entirely to the input/native form.
        if (key === "ArrowDown") {
          schedule();
          if (open) {
            e.preventDefault();
            move(1);
          }
        }
        return;
      }
      if (key === "ArrowDown") {
        e.preventDefault();
        move(1);
      } else if (key === "ArrowUp") {
        e.preventDefault();
        move(-1);
      } else if (key === "Enter") {
        // STRICT gate: only swallow Enter (suppressing native submit) when a row is highlighted.
        if (active >= 0) {
          e.preventDefault();
          accept(active);
        }
        // else: no highlight -> let Enter fall through to native form submission (n=7,8,9).
      } else if (key === "Escape") {
        if (open) {
          e.preventDefault();
          hide();
        }
      } else if (key === "Tab") {
        // accept the highlighted row on Tab without blocking focus traversal otherwise
        if (active >= 0) {
          e.preventDefault();
          accept(active);
        } else {
          hide();
        }
      }
    }

    // input/keyup drive the (debounced) suggestion lookup on a channel independent of submit
    // (n=1-6,24). We skip the navigation/commit keys on keyup so they don't re-fire a fetch.
    function onInput() {
      schedule();
    }
    function onKeyup(e) {
      var key = e.key;
      if (
        key === "ArrowDown" ||
        key === "ArrowUp" ||
        key === "Enter" ||
        key === "Escape" ||
        key === "Tab"
      ) {
        return;
      }
      schedule();
    }

    function onPointerDown(e) {
      // mousedown on a row: accept it. preventDefault keeps DOM focus on the input (no blur).
      var row = e.target.closest ? e.target.closest(".qb-pop-row") : null;
      if (!row) return;
      e.preventDefault();
      var rows = pop.children;
      for (var i = 0; i < rows.length; i++) {
        if (rows[i] === row) {
          accept(i);
          return;
        }
      }
    }

    function onBlur() {
      // close on blur, but defer so a row pointerdown can accept first
      setTimeout(function () {
        if (document.activeElement !== input) hide();
      }, 0);
    }

    input.addEventListener("input", onInput);
    input.addEventListener("keyup", onKeyup);
    input.addEventListener("keydown", onKeydown);
    input.addEventListener("blur", onBlur);
    pop.addEventListener("mousedown", onPointerDown);

    // start closed; ARIA combobox baseline
    pop.hidden = true;
    input.setAttribute("aria-expanded", "false");

    return {
      // called by the overlay engine's submit guard (n=11): clear pending debounce + in-flight
      // fetch so a stale suggestion can never resolve after the form has been submitted.
      cancel: function () {
        if (debounceTimer) {
          clearTimeout(debounceTimer);
          debounceTimer = 0;
        }
        cancelInFlight();
      },
      hide: hide,
      isOpen: function () {
        return open;
      },
      destroy: function () {
        detached = true;
        if (debounceTimer) {
          clearTimeout(debounceTimer);
          debounceTimer = 0;
        }
        cancelInFlight();
        input.removeEventListener("input", onInput);
        input.removeEventListener("keyup", onKeyup);
        input.removeEventListener("keydown", onKeydown);
        input.removeEventListener("blur", onBlur);
        pop.removeEventListener("mousedown", onPointerDown);
        clearPop();
        pop.hidden = true;
        input.removeAttribute("aria-activedescendant");
        input.setAttribute("aria-expanded", "false");
      },
    };
  }

  // normalize a /api/search/fields payload ([{name,type,...}]) or a bare string[] into
  // [{ name, type }]; ignore anything malformed.
  function normalizeFields(raw) {
    if (!Array.isArray(raw)) return [];
    var out = [];
    for (var i = 0; i < raw.length; i++) {
      var f = raw[i];
      if (typeof f === "string") {
        out.push({ name: f, type: "field" });
      } else if (f && typeof f.name === "string") {
        out.push({ name: f.name, type: typeof f.type === "string" ? f.type : "field" });
      }
    }
    return out;
  }

  // ---------------------------------------------------------------------------
  // Overlay engine. Resolves the named input, owns a mirror <pre> overlay, colors tokens,
  // and keeps the overlay scroll-aligned with the input. NEVER re-creates the input (no
  // cloneNode/replaceWith/innerHTML re-render, n=19-23); only addEventListener + append.
  function createEngine(input, overlay, opts) {
    opts = opts || {};
    var raf = 0;
    var detached = false;

    // Optional cursor-context suggestion layer. Only wired when a popover element is supplied
    // (opts.pop); otherwise the bar is tokenizer+overlay only. The suggestion engine attaches its
    // own input/keyup/keydown listeners — additive, never re-creating the input (n=19-22).
    var suggest = null;
    if (opts.pop) {
      suggest = createSuggestions(input, opts.pop, opts);
    }

    // n=11 layer-1: clear the pending value-fetch debounce + abort any in-flight suggestion fetch
    // the moment the form is submitted, so a stale suggestion can never resolve afterwards. This
    // listener is purely additive — it NEVER preventDefault/stopImmediatePropagation/re-dispatches
    // (n=20,21,24) — so the existing app.js submit handler runs untouched on its own channel.
    var submitForm = opts.form || (input.form ? input.form : null);
    function onSubmit() {
      if (suggest) suggest.cancel();
    }
    if (suggest && submitForm) {
      submitForm.addEventListener("submit", onSubmit);
    }

    // schedule a coalesced paint — writes overlay.innerHTML (a layout-invalidating write)
    // then aligns scroll. We never *read* caret geometry here, so no forced reflow (n=14):
    // the overlay mirrors the input's own box model via CSS, so char alignment is free.
    function paint() {
      if (detached) return;
      overlay.innerHTML = renderTokensHtml(input.value);
      // passive read of the input's own scrollLeft (cheap; no layout-invalidating write
      // precedes it within this frame) then mirror it onto the overlay.
      overlay.scrollLeft = input.scrollLeft;
      overlay.scrollTop = input.scrollTop;
      if (typeof opts.onPaint === "function") {
        try {
          opts.onPaint(input.value);
        } catch (e) {
          /* never let a hook break the bar */
        }
      }
    }

    function schedulePaint() {
      if (raf) return;
      if (typeof window.requestAnimationFrame === "function") {
        raf = window.requestAnimationFrame(function () {
          raf = 0;
          paint();
        });
      } else {
        raf = 1;
        paint();
        raf = 0;
      }
    }

    function onInput() {
      schedulePaint();
    }
    function onScroll() {
      // keep the colored layer locked to the input's scroll position
      overlay.scrollLeft = input.scrollLeft;
      overlay.scrollTop = input.scrollTop;
    }

    input.addEventListener("input", onInput);
    input.addEventListener("scroll", onScroll);
    input.addEventListener("focus", schedulePaint);

    // initial paint — handles a pre-filled / restored value
    paint();

    return {
      refresh: paint,
      tokenize: function () {
        return tokenize(input.value);
      },
      parse: function () {
        return parse(input.value);
      },
      balance: function () {
        return balance(input.value);
      },
      suggestions: suggest,
      destroy: function () {
        detached = true;
        if (raf && typeof window.cancelAnimationFrame === "function") {
          window.cancelAnimationFrame(raf);
        }
        raf = 0;
        input.removeEventListener("input", onInput);
        input.removeEventListener("scroll", onScroll);
        input.removeEventListener("focus", schedulePaint);
        if (suggest && submitForm) submitForm.removeEventListener("submit", onSubmit);
        if (suggest) suggest.destroy();
      },
    };
  }

  // Resolve the input named "query" inside `form` without an id, per n=23.
  function resolveInput(form, options) {
    if (options && options.input) return options.input;
    if (!form) return null;
    var named = form.querySelector('input[name="query"]');
    if (named) return named;
    if (form.elements && typeof form.elements.namedItem === "function") {
      var el = form.elements.namedItem("query");
      // a NodeList comes back if multiple share the name; take the first element
      if (el && el.tagName) return el;
      if (el && el.length) return el[0];
    }
    return null;
  }

  // Find an existing .qb-overlay sibling, or create one and insert it *before* the input
  // (z-index in CSS puts the transparent-text input above it). We only append a sibling —
  // we never touch the input node itself.
  function resolveOverlay(input, options) {
    if (options && options.overlay) return options.overlay;
    var container = input.parentNode;
    if (!container) return null;
    var existing = container.querySelector(".qb-overlay");
    if (existing) return existing;
    var pre = document.createElement("pre");
    pre.className = "qb-overlay";
    pre.setAttribute("aria-hidden", "true");
    // insert before the input so it sits behind it in source order (CSS handles z-index)
    container.insertBefore(pre, input);
    return pre;
  }

  // ---------------------------------------------------------------------------
  // Public entry point. attach(form, options) -> { refresh, tokenize, parse, balance,
  // suggestions, destroy }. options: { input?, overlay?, onPaint?, pop?, fields?, onAccept? }.
  // When a popover element is supplied (options.pop), the cursor-context suggestion layer is
  // wired; otherwise the bar is tokenizer + coloring overlay only. Returns null if the named
  // input is absent. The `form` (positional) is forwarded so the suggestion layer can clear its
  // pending value-fetch debounce on submit (n=11) without ever touching the submit path.
  function attach(form, options) {
    options = options || {};
    var input = resolveInput(form, options);
    if (!input) return null;
    var overlay = resolveOverlay(input, options);
    if (!overlay) return null;
    // forward the positional form into opts so the suggestion layer's submit-cancel can bind.
    var engineOpts = options.form ? options : assignForm(options, form);
    return createEngine(input, overlay, engineOpts);
  }

  // shallow-copy options with `form` set — never mutate the caller's object.
  function assignForm(options, form) {
    var out = {};
    for (var k in options) {
      if (Object.prototype.hasOwnProperty.call(options, k)) out[k] = options[k];
    }
    out.form = form;
    return out;
  }

  // ---------------------------------------------------------------------------
  // Backward-compat adapter for the existing wiring (app.js calls .mount({input, overlay,
  // pop, form, fields})). Both mount and attach now wire the same cursor-context suggestion
  // layer; mount simply forwards its flat opts bag through to the shared engine.
  function mount(opts) {
    opts = opts || {};
    var input = opts.input || resolveInput(opts.form, opts);
    if (!input) return { destroy: function () {} };
    var overlay = opts.overlay || resolveOverlay(input, opts);
    if (!overlay) return { destroy: function () {} };
    return createEngine(input, overlay, opts);
  }

  window.BlackBoxQueryBar = {
    attach: attach,
    mount: mount,
    tokenize: tokenize,
    parse: parse,
    balance: balance,
    renderTokensHtml: renderTokensHtml,
    _reduceMotion: reduceMotion,
  };
})();
