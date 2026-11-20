// SPDX-License-Identifier: MPL-2.0

// Safari 27.2, and every browser on iOS and iPadOS 27.2, can corrupt float arguments when JavaScript makes a tail call
// into WebAssembly (WebKit bug 329421, https://bugs.webkit.org/show_bug.cgi?id=329421). Every skiko.mjs export wrapper
// is such a call, so Skia receives wrong coordinates and the app paints a white page with no error (Compose issue
// CMP-10986, https://youtrack.jetbrains.com/issue/CMP-10986). The loader rewrites the wrappers so their calls are not
// in tail position. The plugin fails the build if a tail-call wrapper still reaches a bundle, for example after a
// Skiko update changes the wrapper format or the minifier folds the rewrite back. Other browsers do not implement
// proper tail calls, so for them the only difference is one stack frame.
//
// Remove this file and app/webpack/skiko-no-tail-call-loader.cjs once the supported Safari and iOS versions include
// the WebKit fix.
//
// A block, not a function expression: Kotlin concatenates this file into the generated config, where a leading `(`
// would continue the previous statement.
{
    const path = require("path");
    const fs = require("fs");

    // Webpack runs this config from build/wasm/packages/<package>, four levels below the repository root.
    const loader = path.resolve(__dirname, "../../../../app/webpack/skiko-no-tail-call-loader.cjs");
    if (!fs.existsSync(loader)) {
        throw new Error(`Skiko tail-call loader not found at ${loader}`);
    }
    config.module.rules.push({
        test: /[\\/]skiko\.mjs$/,
        loader,
    });

    // Matches the wrapper before and after minification, for example `(...e)=>(j=k._.malloc)(...e)`.
    const tailCallWrapper =
        /\(\s*\.\.\.([\w$]+)\s*\)\s*=>\s*\(\s*[\w$]+\s*=\s*[\w$]+\._\s*(?:\.[\w$]+|\[\s*(["'])[^"']+\2\s*\])\s*\)\s*\(\s*\.\.\.\1\s*\)/;
    const pluginName = "SkikoNoTailCallCheck";
    config.plugins.push({
        apply(compiler) {
            const { Compilation, WebpackError } = compiler.webpack;
            compiler.hooks.thisCompilation.tap(pluginName, (compilation) => {
                compilation.hooks.processAssets.tap(
                    { name: pluginName, stage: Compilation.PROCESS_ASSETS_STAGE_REPORT },
                    (assets) => {
                        for (const name of Object.keys(assets)) {
                            if (!/\.m?js$/.test(name)) continue;
                            const match = tailCallWrapper.exec(assets[name].source().toString());
                            if (match) {
                                compilation.errors.push(
                                    new WebpackError(
                                        `${name} still calls Skiko through a tail call (${match[0]}), which leaves ` +
                                            "Safari 27.2 with a white page. Update the WebKit 329421 workaround in " +
                                            "app/webpack.config.d/skiko-no-tail-call.js.",
                                    ),
                                );
                            }
                        }
                    },
                );
            });
        },
    });
}
