// 区域截图 vs 全屏截图：对比耗时与区域找图
log('区域截图对比');

var t0 = Date.now();
for (var i = 0; i < 20; i++) { refresh(); }
var full = (Date.now() - t0) / 20;
log('全屏 refresh 平均 ' + full.toFixed(1) + ' ms');

t0 = Date.now();
for (var i = 0; i < 20; i++) { refresh(0, 100, 720, 300); }
var part = (Date.now() - t0) / 20;
log('区域 720x300 refresh 平均 ' + part.toFixed(1) + ' ms');

t0 = Date.now();
for (var i = 0; i < 20; i++) { refresh(0, 100, 200, 100); }
var tiny = (Date.now() - t0) / 20;
log('区域 200x100 refresh 平均 ' + tiny.toFixed(1) + ' ms');

// 区域内找图（模板要落在该区域内）
refresh();
var pt = findImageAsset('templates/title.png', 0.9);
log('全屏找图 ' + pt);
var pt2 = findImageAssetRegion('templates/title.png', 0.9, 0, 100, 720, 300);
log('区域找图 ' + pt2);
'ok';
