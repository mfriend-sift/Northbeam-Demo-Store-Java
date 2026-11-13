// A small YAML reader, just enough for contract/openapi.yaml (block maps and lists, one-line flow
// maps and lists, quoted and plain scalars, "|" text blocks). It exists so contract-test.mjs needs
// no dependency. It is not a general YAML parser.

function scalar(text) {
  const t = text.trim();
  if (/^"(.*)"$/.test(t)) return JSON.parse(t);
  if (/^'(.*)'$/.test(t)) return t.slice(1, -1).replace(/''/g, "'");
  if (t === "true") return true;
  if (t === "false") return false;
  if (t === "null" || t === "~" || t === "") return null;
  if (/^-?\d+(\.\d+)?$/.test(t)) return Number(t);
  return t;
}

// Parses one flow value starting at s[i]; returns [value, nextIndex].
function flow(s, i) {
  const skip = () => { while (s[i] === " ") i++; };
  skip();
  if (s[i] === "{" || s[i] === "[") {
    const isMap = s[i] === "{";
    const close = isMap ? "}" : "]";
    const out = isMap ? {} : [];
    i++;
    for (;;) {
      skip();
      if (s[i] === close) return [out, i + 1];
      if (isMap) {
        const m = /^("[^"]*"|[^:,{}\[\]]+?)\s*:\s*/.exec(s.slice(i));
        const key = scalar(m[1]);
        i += m[0].length;
        const [v, j] = flow(s, i);
        out[key] = v;
        i = j;
      } else {
        const [v, j] = flow(s, i);
        out.push(v);
        i = j;
      }
      skip();
      if (s[i] === ",") i++;
    }
  }
  if (s[i] === '"') {
    let j = i + 1;
    while (s[j] !== '"') j += s[j] === "\\" ? 2 : 1;
    return [JSON.parse(s.slice(i, j + 1)), j + 1];
  }
  let j = i;
  while (j < s.length && !",}]".includes(s[j])) j++;
  return [scalar(s.slice(i, j)), j];
}

const value = (text) => (/^[{\[]/.test(text.trim()) ? flow(text.trim(), 0)[0] : scalar(text));
const KEY = /^("[^"]*"|'[^']*'|[^\s:][^:]*?)\s*:(?:\s+(.*))?$/;

export function parseYaml(source) {
  const lines = source.split("\n").map((raw) => ({ raw, indent: raw.length - raw.trimStart().length, text: raw.trim() }))
    .filter((l) => l.text && !l.text.startsWith("#"));
  let pos = 0;

  function block(indent) {
    return lines[pos].text.startsWith("- ") || lines[pos].text === "-" ? list(indent) : map(indent);
  }

  function list(indent) {
    const out = [];
    while (pos < lines.length && lines[pos].indent === indent && lines[pos].text.startsWith("-")) {
      const rest = lines[pos].text.slice(1).trim();
      if (KEY.test(rest) && !/^[{\["]/.test(rest)) {
        // "- key: value" starts a map whose later keys are indented two more columns.
        lines[pos] = { raw: "", indent: indent + 2, text: rest };
        out.push(map(indent + 2));
      } else { pos++; out.push(value(rest)); }
    }
    return out;
  }

  function map(indent) {
    const out = {};
    while (pos < lines.length && lines[pos].indent === indent && !lines[pos].text.startsWith("- ")) {
      const m = KEY.exec(lines[pos].text);
      if (!m) throw new Error(`mini-yaml: cannot read line: ${lines[pos].text}`);
      const key = scalar(m[1]);
      const rest = (m[2] || "").trim();
      pos++;
      if (rest === "|") {
        const body = [];
        while (pos < lines.length && lines[pos].indent > indent) body.push(lines[pos++].text);
        out[key] = body.join("\n");
      } else if (rest) out[key] = value(rest);
      else if (pos < lines.length && lines[pos].indent > indent) out[key] = block(lines[pos].indent);
      else out[key] = null;
    }
    return out;
  }

  return block(lines[0].indent);
}
