// 我的第一个脚本 —— 用 python tools/whx.py watch scripts/hello.js 跑起来
//
// 保存这个文件，watch 会自动推送到设备并运行，日志直接回显在 PC 终端。
// API 清单见 docs/api.md，改动存盘即生效。

log('你好，WHX');
refresh();                                   // 抓一帧

var w = screenWidth(), h = screenHeight();
log('屏幕 ' + w + 'x' + h);

// 1) 取色
var c = getColor(w / 2, h / 2);
log('屏幕中心颜色 ' + c);

// 2) 找色（找不到返回 null，要自己判断）
var pt = findColor(c, 6);
if (pt === null) {
  warn('没找到这个颜色');
} else {
  log('找到了：' + pt);
}

// 3) 读界面文字（需要先 python tools/whx.py ui enable）
if (uiReady()) {
  log('界面文本节点 ' + texts().split(';').length + ' 个');
}

// 4) 找到「设置」并点开（把 com.android.settings 换成你要的应用）
if (appInstalled('com.android.settings')) {
  launchApp('com.android.settings');
  sleep(800);
  var target = scrollFindText('电池', false, 5);
  log('「电池」→ ' + target);
  if (target !== null) {
    clickText('电池', false);
    sleep(500);
    log('当前前台 ' + currentPackage());
  }
}

'完成';
