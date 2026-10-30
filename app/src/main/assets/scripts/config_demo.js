// 小精灵配置演示：参数由打包时的 config.txt 声明，界面上生成输入框
log('配置演示开始');
log('x = ' + config('x'));
log('y = ' + config('y'));
log('delay = ' + config('delay'));
log('debug = ' + config('debug'));
log('hasConfig(missing) = ' + hasConfig('missing'));

refresh();
log('屏幕 ' + screenWidth() + 'x' + screenHeight());
click(Number(config('x')), Number(config('y')));
sleep(Number(config('delay')) * 100);
log('已按配置点击 ' + config('x') + ',' + config('y'));
'ok';
