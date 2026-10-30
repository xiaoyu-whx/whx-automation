// 内置示例脚本：演示截图、取色、找色、点击、日志全链路
// 这是"内置脚本模式"的样例，打包进 APK 的 assets/scripts 目录。

log('示例脚本开始');
refresh();

var w = screenWidth(), h = screenHeight();
log('屏幕 ' + w + 'x' + h);

var t0 = Date.now();
for (var i = 0; i < 500; i++) {
  getColor(w / 2, h / 2);
}
log('500 次取色耗时 ' + (Date.now() - t0) + ' ms');

var center = getColor(w / 2, h / 2);
log('中心像素 ' + center);

var pt = findColor(center, 6);
if (pt === null) {
  warn('没找到目标颜色');
} else {
  log('找到目标 ' + pt);
  var xy = pt.split(',');
  click(Number(xy[0]), Number(xy[1]));
  sleep(120);
  log('已点击');
}

'完成';
