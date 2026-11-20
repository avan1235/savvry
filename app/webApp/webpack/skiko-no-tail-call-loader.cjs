// SPDX-License-Identifier: MPL-2.0

// Rewrites each skiko.mjs export wrapper so its call into WebAssembly is not in tail position. Safari 27.2 corrupts
// float arguments on such calls (WebKit bug 329421); webpack.config.d/skiko-no-tail-call.js explains the workaround.
//
// Before: export let name = (...a) => (name = loadedWasm._["name"])(...a);
// After:  export let name = (...a) => { try { return (name = loadedWasm._["name"])(...a); } catch (e) { throw e; } };
//
// A call inside `try` is never a tail call, and rethrowing keeps the original error. The wrapper still replaces its
// own export with the Wasm function on first use, as before.
const exportWrapper = /^export let ([\w$]+) = \(\.\.\.a\) => \(\1 = loadedWasm\._\["\1"\]\)\(\.\.\.a\);?$/gm;
const anyTailCallWrapper = /=> \([\w$]+ = loadedWasm\._\[/g;

module.exports = function skikoNoTailCallLoader(source) {
    let patched = 0;
    const result = source.replace(exportWrapper, (_, name) => {
        patched++;
        return `export let ${name} = (...a) => { try { return (${name} = loadedWasm._["${name}"])(...a); } ` +
            "catch (e) { throw e; } };";
    });
    const remaining = (result.match(anyTailCallWrapper) || []).length;
    if (patched === 0 || remaining > 0) {
        throw new Error(
            `skiko.mjs export wrappers changed format (${patched} patched, ${remaining} left in tail position); ` +
                "update the WebKit 329421 workaround in app/webpack",
        );
    }
    return result;
};
