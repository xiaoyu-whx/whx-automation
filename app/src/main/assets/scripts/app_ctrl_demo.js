// 应用控制：启动 / 查询前台 / 强停
log('应用控制演示');

log('设置已安装? ' + appInstalled('com.android.settings'));
log('不存在的应用已安装? ' + appInstalled('com.not.exist.app'));

var now = launchApp('com.android.settings');
log('启动后前台 = ' + now);

log('currentPackage() = ' + currentPackage());

var code = killApp('com.android.settings');
log('killApp 退出码 = ' + code);
sleep(500);
log('强停后前台 = ' + currentPackage());
'ok';
