// Compile the bounded JSON Schema assertion subset used by tagged payload alternatives.
// Unknown keywords must fail generation, never silently weaken a boundary.
export function alternatives(branches, target, node) {
  const kt = target === 'kotlin';
  return branches.map(({ properties, required }) => '(' + Object.entries(properties).map(([key, schema]) => {
    const n = kt ? `${node}.path(${JSON.stringify(key)})` : `${node}.${key}`;
    const rules = Object.entries(schema).sort(([a], [b]) => Number(b === 'type') - Number(a === 'type')).map(([keyword, value]) => {
      switch (keyword) {
        case 'type': {
          const expr = {string: kt ? `${n}.isTextual` : `typeof ${n} === 'string'`, array: kt ? `${n}.isArray` : `Array.isArray(${n})`, null: kt ? `${n}.isNull` : `${n} === null`}[value];
          if (expr) return expr;
          break;
        }
        case 'const': if (typeof value === 'boolean') return kt ? `(${n}.isBoolean && ${n}.booleanValue() == ${value})` : `${n} === ${value}`; break;
        case 'pattern': return kt ? `Regex(${JSON.stringify(value)}).containsMatchIn(${n}.asText())` : `new RegExp(${JSON.stringify(value)}).test(${n})`;
        case 'minItems': return kt ? `${n}.size() >= ${value}` : `${n}.length >= ${value}`;
        case 'minLength': return kt ? `${n}.asText().length >= ${value}` : `${n}.length >= ${value}`;
      }
      throw new Error(`Unsupported payload assertion: ${keyword}=${value}`);
    });
    const absent = kt ? `${n}.isMissingNode` : `${n} === undefined`;
    return '(' + (required.includes(key) ? '' : absent + ' || ') + rules.join(' && ') + ')';
  }).join(' && ') + ')').join(' || ');
}
