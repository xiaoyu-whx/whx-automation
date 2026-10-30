// 压测脚本：循环「截图 → 找色 → 点击」测一轮耗时，用于阶段八性能基线与长跑内存观察。
// 注意：点击目标固定在左下角空白处，避免误触其他应用（早期版本点"找到的颜色位置"，
// 结果把系统设置点开了 —— 压测脚本自己也要避免副作用）。
log('压测开始');

var rounds = 60;
var W = 360, H = 1250;      // 固定安全点（屏幕底部空白区）
var t0 = Date.now();
var found = 0;

for (var i = 0; i < rounds; i++) {
  refresh();
  var c = getColor(W, H);
  var pt = findColor(c, 6);
  if (pt !== null) {
    found++;
  }
  click(W, H);
}

var ms = Date.now() - t0;
log(rounds + ' 轮用时 ' + ms + ' ms，平均 ' + (ms / rounds).toFixed(1) + ' ms/轮，命中 ' + found);
'ok';
