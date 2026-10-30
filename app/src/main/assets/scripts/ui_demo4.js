// 按真实资源 id 定位（android:id/title 是列表行标题）
log('ui_demo4 开始');
var byId = findId('android:id/title');
log('android:id/title → ' + byId);
log('可点吗（返回坐标即代表会点）')
var byId2 = findId('com.android.settings:id/dashboard_container');
log('dashboard_container → ' + byId2);
'ok';
