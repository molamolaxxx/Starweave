'use strict';
const fs = require('node:fs');
const path = require('node:path');

// The upstream 6.0.0 dialog has no API for system-bar colors. Apply this narrow,
// version-checked customization to generated Android source on every prepare.
module.exports = function (context) {
  const file = path.join(context.opts.projectRoot, 'platforms/android/app/src/main/java/org/apache/cordova/inappbrowser/InAppBrowser.java');
  if (!fs.existsSync(file)) return;
  const upstream = path.join(context.opts.projectRoot, 'node_modules/cordova-plugin-inappbrowser/src/android/InAppBrowser.java');
  const version = require(path.join(context.opts.projectRoot, 'node_modules/cordova-plugin-inappbrowser/package.json')).version;
  if (version !== '6.0.0') throw new Error('Review Android window customization before changing InAppBrowser version.');
  let source = fs.readFileSync(upstream, 'utf8');
  function replaceOnce(from, to) {
    if (source.split(from).length !== 2) throw new Error('InAppBrowser source changed: Android window customization does not match.');
    source = source.replace(from, to);
  }
  replaceOnce('    private InAppBrowserDialog dialog;', `    private InAppBrowserDialog dialog;

    private void applyStarweaveSystemBars(final boolean dark) {
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override public void run() {
                if (dialog == null || dialog.getWindow() == null) return;
                Window window = dialog.getWindow();
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                        | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                int color = android.graphics.Color.parseColor(dark ? "#0b1020" : "#f5f5fc");
                window.setStatusBarColor(color);
                window.setNavigationBarColor(!dark && android.os.Build.VERSION.SDK_INT < 26
                        ? android.graphics.Color.parseColor("#0b1020") : color);
                int flags = dark ? 0 : android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (!dark && android.os.Build.VERSION.SDK_INT >= 26) flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                window.getDecorView().setSystemUiVisibility(flags);
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    window.setStatusBarContrastEnforced(false);
                    window.setNavigationBarContrastEnforced(false);
                }
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        });
    }`);
  replaceOnce('                dialog.setCancelable(true);', '                applyStarweaveSystemBars(true);\n                dialog.setCancelable(true);');
  replaceOnce('                            obj.put("data", new JSONObject(data));', `                            JSONObject message = new JSONObject(data);
                            if ("starweave-theme".equals(message.optString("type"))) {
                                applyStarweaveSystemBars("dark".equals(message.optString("theme")));
                                return;
                            }
                            obj.put("data", message);`);
  fs.writeFileSync(file, source);
};
