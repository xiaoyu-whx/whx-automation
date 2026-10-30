// 按文字点击演示：找到「电池」并点开
log('按文字点击');
var pt = clickText('电池', false);
log('点击结果 ' + pt);
sleep(800);
log('界面是否已变化：' + findText('电池', false));
'ok';
