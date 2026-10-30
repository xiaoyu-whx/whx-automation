// 无障碍演示：读取界面文字、按文字点击
log('无障碍就绪 = ' + uiReady());

var all = texts();
var count = (all === '') ? 0 : all.split(';').length;
log('界面文本节点 ' + count + ' 个');
if (count > 0) {
  log('前 3 条: ' + all.split(';').slice(0, 3).join(' || '));
}

var pt = findText('设置', false);
log('找「设置」→ ' + pt);
'ok';
