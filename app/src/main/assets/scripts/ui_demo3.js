// 无障碍：按 id 定位 + 滚动查找
log('ui_demo3 开始');

// 1) 按资源 id 定位（比文字稳）
var byId = findId('com.android.settings:id/search_action_bar');
log('按 id 找搜索栏 → ' + byId);

// 2) 滚动查找（系统设置列表一屏放不下）
var found = scrollFindText('系统', false, 6);
log('滚动查找「系统」→ ' + found);

'ok';
