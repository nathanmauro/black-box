export const JSON_NUMBER_SCAN_LIMIT = 32_768;

/** Scan already-valid JSON without parsing numeric lexemes through Number first. */
export function scanJsonIdentity(value: string): {
  compact: string;
  safeNumbers: boolean;
  depth: number;
  negativeZero: boolean;
} {
  const pieces: string[] = [];
  const numberToken = /-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/y;
  let safeNumbers = true;
  let negativeZero = false;
  let depth = 0;
  let maxDepth = 0;
  let index = 0;
  while (index < value.length) {
    const char = value[index];
    if (char === '"') {
      const start = index++;
      while (index < value.length) {
        if (value[index] === "\\") index += 2;
        else if (value[index++] === '"') break;
      }
      pieces.push(value.slice(start, index));
    } else if (char === "-" || (char >= "0" && char <= "9")) {
      numberToken.lastIndex = index;
      const token = numberToken.exec(value)?.[0];
      // Callers pass validated JSON; retain raw identity if that precondition ever drifts.
      if (!token) return { compact: value, safeNumbers: false, depth: maxDepth, negativeZero };
      safeNumbers &&= decimalNumberRoundtrips(token) === true;
      negativeZero ||= Object.is(Number(token), -0);
      pieces.push(token);
      index += token.length;
    } else {
      if (char === "{" || char === "[") maxDepth = Math.max(maxDepth, ++depth);
      else if (char === "}" || char === "]") depth--;
      if (char !== " " && char !== "\t" && char !== "\r" && char !== "\n") pieces.push(char);
      index++;
    }
  }
  return { compact: pieces.join(""), safeNumbers, depth: maxDepth, negativeZero };
}

/** Undefined means a nondecimal form or exponent outside the bounded normalization policy. */
export function decimalNumberRoundtrips(token: string): boolean | undefined {
  const decimal = normalizedDecimal(token);
  if (decimal === undefined) return undefined;
  const numeric = Number(token);
  return Number.isFinite(numeric) && decimal === normalizedDecimal(String(numeric));
}

/** Sign + significant digits + decimal exponent, with no expansion of powers of ten. */
function normalizedDecimal(token: string): string | undefined {
  const match = /^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(token);
  if (!match) return undefined;
  const fraction = match[3] ?? "";
  const digits = match[2] + fraction;
  let end = digits.length;
  while (end > 0 && digits[end - 1] === "0") end--;
  const significant = digits.slice(0, end).replace(/^0+/, "");
  if (!significant) return "0"; // Preserve existing semantic equivalence of -0 and 0.
  const exponent = match[4] ?? "0";
  // Larger exponents cannot be offset by either caller's bounded payload digits. Conservatively keep
  // raw identity, and never allocate an expanded decimal or an unbounded BigInt exponent.
  if (exponent.replace(/^[+-]?0*/, "").length > 6) return undefined;
  return `${match[1]}${significant}e${Number(exponent) - fraction.length + digits.length - end}`;
}
