// 缩放无关找图：模板是 720p 抓的，在任意分辨率下都要能命中
log('缩放无关找图');
refresh();
log('当前屏幕 ' + screenWidth() + 'x' + screenHeight());
var a = findImageAsset('templates/title.png', 0.9);
log('原尺寸找图 → ' + a);
var b = findImageAuto('templates/title.png', 0.9);
log('自适应找图 → ' + b);
'ok';
