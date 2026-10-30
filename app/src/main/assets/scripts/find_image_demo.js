// 找图演示：模板来自 APK 内 assets/templates/title.png
log('找图演示开始');
refresh();
log('屏幕 ' + screenWidth() + 'x' + screenHeight());

var pt = findImageAsset('templates/title.png', 0.9);
if (pt === null) {
  warn('没找到标题区域');
} else {
  log('标题命中中心 ' + pt);
  var xy = pt.split(',');
  click(Number(xy[0]), Number(xy[1]));
  sleep(200);
  log('已点击标题');
}

// 拿不到的模板必须报错，而不是静默返回 null
try {
  findImageAsset('templates/不存在.png', 0.9);
  warn('这里不应该走到');
} catch (e) {
  log('缺失模板正确报错: ' + e);
}
'ok';
