// 自动点掉 root 授权弹窗：先选「永久记住选择」，再点「允许」。
// 用途：CI / 冒烟测试里给新装的小精灵授权，不用人在旁边点。
// 注意：选完单选框后界面会重绘，节点树短暂不可用，所以「允许」要轮询等它出现。
log('尝试自动授权 root，无障碍就绪 = ' + uiReady());

if (!uiReady()) {
  warn('无障碍未启用，无法自动授权（先跑 whx ui enable）');
} else {
  var remember = findText('永久记住选择', false);
  if (remember !== null) {
    clickText('永久记住选择', false);
    log('已选「永久记住选择」');
  } else {
    log('没看到选项（可能弹窗还没出来）');
  }

  var allow = null;
  for (var i = 0; i < 15; i++) {
    allow = findText('允许', true);
    if (allow !== null) break;
    sleep(200);
  }
  if (allow !== null) {
    log('点击「允许」→ ' + clickText('允许', true));
  } else {
    warn('等不到「允许」按钮');
  }
}
'ok';
