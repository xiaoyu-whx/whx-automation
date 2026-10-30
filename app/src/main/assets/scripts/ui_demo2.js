// 无障碍进阶：多匹配 + 可点击祖先
log('无障碍进阶');
log('就绪 = ' + uiReady());

var all = findTextAll('电池', false);
log('「电池」匹配数 → ' + (all === '' ? 0 : all.split(';').length));
log('坐标们 ' + all);

var one = findText('电池', false);
log('findText 返回 ' + one + '（应是可点击行中心，不是文字本身）');

var pt = clickText('电池', false);
log('clickText ' + pt);
sleep(600);
log('点后还能找到「电池」吗：' + findText('电池', false));
'ok';
