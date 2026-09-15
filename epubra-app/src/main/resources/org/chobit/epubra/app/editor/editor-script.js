(function () {
  var INJECTED_IDS = ['%s', '%s', '%3$s'];
  function stripInjected(root) {
    for (var i = 0; i < INJECTED_IDS.length; i++) {
      var el = root.querySelector('#' + INJECTED_IDS[i]);
      if (el && el.parentNode) { el.parentNode.removeChild(el); }
    }
  }
  // 每次加载都会往 head 注入 base / style / script 三个元素，其中 style 与
  // script 各带一个换行；stripInjected 只删元素、把相邻的空白文本节点留在
  // head 里，回写进章节后下一轮再注入再剥离，空行就跨周期线性累积（#59）。
  // 这里把 head 内的空白收敛成「每个间隙一个换行」：既阻断累积，又能自愈
  // 历史上已被撑开的 head。head 只放元数据、空白无语义，规范化不影响渲染；
  // body（含 pre/code 的缩进）一律不动，保留作者排版。
  function tidyHeadWhitespace(root) {
    var head = root.querySelector('head');
    if (!head) { return; }
    for (var c = head.firstChild; c; ) {
      var next = c.nextSibling;
      if (c.nodeType === 3 && !c.data.replace(/\s/g, '')) {
        var prev = c.previousSibling;
        if (prev && prev.nodeType === 3 && !prev.data.replace(/\s/g, '')) {
          head.removeChild(c);
        } else {
          c.data = '\n';
        }
      }
      c = next;
    }
  }
  function serialize() {
    var clone = document.documentElement.cloneNode(true);
    stripInjected(clone);
    tidyHeadWhitespace(clone);
    rescueStrayNodes(clone);
    mergeStyledSpans(clone);
    unwrapBareSpans(clone);
    pruneEmptyInline(clone);
    pruneEmptyLists(clone);
    var body = clone.querySelector('body');
    if (body) { body.removeAttribute('contenteditable'); }
    return '<?xml version="1.0" encoding="UTF-8"?>\n' +
           new XMLSerializer().serializeToString(clone);
  }
  // 把 body 之外的元素节点（历史损坏残留：正文块挂在 html 层）收编回 body，
  // 挪回后回写的 XHTML 才合法，下次加载内容才能正常展示
  function rescueStrayNodes(root) {
    var body = root.querySelector('body');
    if (!body) { return; }
    var stray = [];
    for (var c = root.firstChild; c; c = c.nextSibling) {
      // 这里用 nodeName 判定，不用 root.head：文档按 XML 解析时 Element 上
      // 不保证有 head 属性（那是 HTMLDocument 的接口），取不到就是 undefined，
      // 判定恒真 → 会把 head 当成 body 之外的残留节点搬进 body
      if (c.nodeType === 1 && c.nodeName.toLowerCase() !== 'head' && c !== body) {
        stray.push(c);
      }
    }
    for (var i = 0; i < stray.length; i++) { body.appendChild(stray[i]); }
  }
  // 空行内标签是纯噪音（无锚文本的强调、无内容的强调壳），回写前剔除。
  // 循环到不动点，处理 <strong><em></em></strong> 这类嵌套空壳：
  // 内层删掉后外层变空，下一轮接着删。只清强调类语义标签，
  // <a>（有 href 属性语义）不碰。
  // <span> 也一并清：工具条「字体/字号/颜色」写的受控内联样式就挂在 span 上，
  // 一个没有文字的 span 无论带不带 style / class 都是纯噪音（#72）。
  function pruneEmptyInline(root) {
    var tags = 'strong,em,u,del,code,b,i,s,span';
    var changed = true;
    while (changed) {
      changed = false;
      var els = root.querySelectorAll(tags);
      for (var i = 0; i < els.length; i++) {
        var el = els[i];
        var hasMedia = el.querySelector('img,br,hr,video,audio,iframe,object,svg,math');
        if (!hasMedia && !el.textContent.replace(/\s/g, '')) {
          el.parentNode.removeChild(el);
          changed = true;
        }
      }
    }
  }
  // 空列表是纯噪音：<ul></ul> / <ol></ol>（零 li），以及 li 里空无一物、
  // 没有任何文字的列表（刚点列表还没写、或把列表项内容删空）。与 pruneEmptyInline
  // 同层——DOM 里允许短暂存在这种编辑中间态（点列表按钮先给个落脚点），
  // 但绝不写进书（#55 的块级延伸）。
  // 循环到不动点：内层空列表删掉后外层 li 可能也空了，下一轮接着删。
  // 注意「有意义的内容」判定里**不含 br**——contenteditable 会给空块塞 <br/>，
  // 那是占位不是内容，算进去会让净化在真实使用中失效。
  function pruneEmptyLists(root) {
    var changed = true;
    while (changed) {
      changed = false;
      var lists = root.querySelectorAll('ul,ol');
      for (var i = 0; i < lists.length; i++) {
        var el = lists[i];
        var hasContent = el.querySelector('img,hr,video,audio,iframe,object,svg,math');
        if (!hasContent && !el.textContent.replace(/\s/g, '')) {
          el.parentNode.removeChild(el);
          changed = true;
        }
      }
    }
  }
  // 工具条写进来的受控内联样式（字体 / 字号 / 颜色）都挂在 span 上，这些 span 只带
  // 一个 style 属性——有 class / id 的是作者自己的标签，一律不碰（styleOnly）。
  function styleOnly(el) {
    var attrs = el.attributes;
    for (var i = 0; i < attrs.length; i++) {
      if (attrs[i].name !== 'style') { return false; }
    }
    return true;
  }
  // 「整段重选后再改一次样式」：选区内容恰好是上一次那个 span，包围操作会再套一层，
  // 嵌套层数随编辑轮次增长。style-only 的 span 套 style-only 的 span 可以无损合并 ——
  // 内层属性并入外层（内层已有值不覆盖外层），再把内层子节点上提。
  // 与 pruneEmptyInline 同层：DOM 里允许编辑中间态，只保证不写进书。
  function mergeStyledSpans(root) {
    var changed = true;
    while (changed) {
      changed = false;
      var spans = root.querySelectorAll('span');
      for (var i = 0; i < spans.length; i++) {
        var outer = spans[i];
        if (outer.childNodes.length !== 1) { continue; }
        var inner = outer.firstChild;
        if (inner.nodeType !== 1 || inner.nodeName.toLowerCase() !== 'span') { continue; }
        if (!styleOnly(outer) || !styleOnly(inner)) { continue; }
        // 内层带着作者自己的声明时放弃合并：合并只搬受管四类，会把它弄丢
        if (hasForeignStyle(outer) || hasForeignStyle(inner)) { continue; }
        var merged = readStyle(outer);
        var extra = readStyle(inner);
        for (var k in extra) { if (!merged[k]) { merged[k] = extra[k]; } }
        writeStyle(outer, merged);
        while (inner.firstChild) { outer.insertBefore(inner.firstChild, inner); }
        outer.removeChild(inner);
        changed = true;
      }
    }
  }
  // 清档（工具条上的「默认」）会把 span 上最后一个受控样式删掉，留下一个不带任何属性的
  // 空壳。无属性的 <span> 对渲染零影响（作者自己写的那种也一样），回写前剥掉。
  function unwrapBareSpans(root) {
    var spans = root.querySelectorAll('span');
    for (var i = 0; i < spans.length; i++) {
      var el = spans[i];
      if (!styleOnly(el) || el.getAttribute('style') || !el.parentNode) { continue; }
      while (el.firstChild) { el.parentNode.insertBefore(el.firstChild, el); }
      el.parentNode.removeChild(el);
    }
  }
  function push() {
    if (window.epubraBridge) { window.epubraBridge.onEdited(serialize()); }
  }
  var timer = null;
  // 与源码编辑器的 600ms 撤销合并保持一致，避免每敲一键就回写一次
  document.addEventListener('input', function () {
    if (timer) { clearTimeout(timer); }
    timer = setTimeout(push, 600);
  });
  document.addEventListener('blur', function () {
    if (timer) { clearTimeout(timer); timer = null; }
    push();
  }, true);
  window.epubraSerialize = serialize;

  // ---- 工具条：对可视化编辑器做真正的富文本操作 ----------------
  //
  // 全部走 Range/DOM 手工操作，不用 document.execCommand：后者产出的标签
  // 随引擎而异（<b> / <span style> / <font>），而这里必须产出确定性的
  // XHTML（<strong> / <em>），否则回写正文会带上引擎私货。
  var NS = 'http://www.w3.org/1999/xhtml';

  function makeTag(name) { return document.createElementNS(NS, name); }
  function tagOf(el) { return (el && el.nodeType === 1) ? el.tagName.toLowerCase() : ''; }
  function isList(el) { var t = tagOf(el); return t === 'ul' || t === 'ol'; }

  function activeSelection() {
    var s = window.getSelection();
    return (s && s.rangeCount) ? s : null;
  }

  function selectContents(el) {
    var s = activeSelection();
    if (!s) { return; }
    var r = document.createRange();
    r.selectNodeContents(el);
    s.removeAllRanges();
    s.addRange(r);
  }

  function collapseInto(el) {
    var s = activeSelection();
    if (!s) { return; }
    var r = document.createRange();
    r.selectNodeContents(el);
    r.collapse(true);
    s.removeAllRanges();
    s.addRange(r);
  }

  // 光标所在的最外层块（body 的直接子元素）。
  // node 不在 body 内（body 被删空后光标落在 body/html 层）时必须返回 null——
  // 旧实现一路上溯会返回 document，后续 moveChildren/replaceChild 就把
  // 整个文档树搬进节点或在 null 父节点上抛错，标签被打到 body 外（#58）。
  function topBlock(node) {
    var n = node;
    if (!n) { return null; }
    if (n.nodeType !== 1) { n = n.parentNode; }
    if (!n || n === document.body) { return null; }
    while (n && n.parentNode && n.parentNode !== document.body) { n = n.parentNode; }
    return (n && n.parentNode === document.body) ? n : null;
  }

  // block 里直接包住 node 的那一层（例如 ul 里的某个 li）
  function childOfContaining(block, node) {
    var n = node;
    while (n && n.parentNode && n.parentNode !== block) { n = n.parentNode; }
    return (n && n.parentNode === block) ? n : null;
  }

  function moveChildren(from, to) {
    while (from.firstChild) { to.appendChild(from.firstChild); }
  }

  // 块级标签集合：这些孩子不能塞进 p/h2/h3 这类行内容器
  //（p>ul、p>p 都会让回写进书的 XHTML 结构损坏，再次加载后内容展示异常）
  function isBlockTag(t) {
    return t === 'p' || t === 'h1' || t === 'h2' || t === 'h3'
            || t === 'ul' || t === 'ol' || t === 'blockquote'
            || t === 'hr' || t === 'div' || t === 'pre' || t === 'table';
  }

  // 把容器的块级孩子摘出来按原序返回，剩下的行内容子再搬运
  function pullOutBlocks(container) {
    var out = [];
    var c = container.firstChild;
    while (c) {
      var next = c.nextSibling;
      if (isBlockTag(tagOf(c))) { out.push(c); container.removeChild(c); }
      c = next;
    }
    return out;
  }

  // 块级节点数组按原序接到 anchor 后面，返回新的追加锚点
  function appendBlocksAfter(blocks, anchor) {
    for (var i = 0; i < blocks.length; i++) {
      anchor.parentNode.insertBefore(blocks[i], anchor.nextSibling);
      anchor = blocks[i];
    }
    return anchor;
  }

  // 把光标所在块换掉当前块；块在列表里则先脱离列表
  function formatBlock(tag) {
    var s = activeSelection();
    if (!s) { return false; }
    var range = s.getRangeAt(0);
    var block = topBlock(range.startContainer);
    if (!block) {
      // body 空 / 光标不在块上：直接在 body 末尾建目标块（同 toggleList，
      // 不返回 false——fallback 会把标签插到源码区去）
      var blank = makeTag(tag);
      document.body.appendChild(blank);
      collapseInto(blank);
      return true;
    }

    if (isList(block)) {
      var item = childOfContaining(block, range.startContainer);
      if (tagOf(item) !== 'li') {
        // 整列表被选中（起点就是列表本身）：与 toggleList 的「退回段落」同语义，
        // 每个 li 各转一段；标题/引用对整列表没有明确语义，按「有意不作为」处理。
        // 两条路径都必须返回 true——返回 false 会让 Java 侧 fallback 往源码区
        // 插骨架，切 tab 时用源码覆盖章节，可视化改动整个冲掉（#57/#60 同族事故）。
        if (tag === 'p') { return !!listToBlocks(block); }
        return true;
      }
      var lifted = makeTag(tag);
      // li 里的块级孩子（Tab 缩进的子列表等）不能进新块——摘出来跟在后面
      var blocks = pullOutBlocks(item);
      moveChildren(item, lifted);
      block.parentNode.insertBefore(lifted, block.nextSibling);
      appendBlocksAfter(blocks, lifted);
      block.removeChild(item);
      if (!block.querySelector('li')) { block.parentNode.removeChild(block); }
      collapseInto(lifted);
      return true;
    }

    if (tagOf(block) === tag) { collapseInto(block); return true; }
    var fresh = makeTag(tag);
    var movedBlocks = [];
    if (tag === 'p' || tag === 'h1' || tag === 'h2' || tag === 'h3') {
      // 引用块转段落等场景：块级孩子绝不能塞进行内容器（p>p 同样非法）
      movedBlocks = pullOutBlocks(block);
    }
    moveChildren(block, fresh);
    block.parentNode.replaceChild(fresh, block);
    appendBlocksAfter(movedBlocks, fresh);
    collapseInto(fresh);
    return true;
  }

  // 整个列表退回段落：每个顶层 li 各转成一段，li 内的块级孩子
  //（子列表等）原样跟在对应段之后——多次点「列表」往返时结构保持合法
  function listToBlocks(block) {
    var frag = document.createDocumentFragment();
    var item = block.firstElementChild;
    while (item) {
      var next = item.nextElementSibling;
      if (tagOf(item) === 'li') {
        var p = makeTag('p');
        var blocks = pullOutBlocks(item);
        moveChildren(item, p);
        frag.appendChild(p);
        appendBlocksAfter(blocks, p);
      } else {
        frag.appendChild(item);
      }
      item = next;
    }
    // 先取 lastChild 再替换：appendChild(frag) 会把子节点移交进 DOM，
    // 替换之后 frag 已空，frag.lastChild 恒为 null（踩实过：返回 false
    // 会让 Java 侧 fallback 往源码区插列表骨架）
    var last = frag.lastChild;
    block.parentNode.replaceChild(frag, block);
    return last;
  }

  // 段落 / 标题 / 列表 三者互转；已经是列表了再点一次退回段落；
  // ul 与 ol 互相点则直接换列表类型，不必先退回段落。
  function toggleList(kind) {
    var s = activeSelection();
    if (!s || !s.rangeCount) { return false; }
    var range = s.getRangeAt(0);
    var block = topBlock(range.startContainer);
    if (!block) {
      // body 空 / 光标不在块上：直接在 body 末尾建列表骨架，
      // 不返回 false（会触发源码 fallback，骨架会被插到源码区去）
      var blankList = makeTag(kind);
      var blankItem = makeTag('li');
      blankList.appendChild(blankItem);
      document.body.appendChild(blankList);
      collapseInto(blankItem);
      return true;
    }
    if (isList(block)) {
      if (tagOf(block) === kind) {
        // 退回段落。整列表被选中时（起点就是列表本身，如整段选中后再点）
        // childOfContaining 找不到 li——此时把每个 li 各转成一段；
        // 若这里返回 false，Java 侧会 fallback 去源码区插列表骨架，
        // 切 tab 时把可视化改动整个冲掉（多次点列表后内容错乱的根源之一）
        var item = childOfContaining(block, range.startContainer);
        if (tagOf(item) !== 'li') {
          return !!listToBlocks(block);
        }
        return formatBlock('p');
      }
      var converted = makeTag(kind);
      moveChildren(block, converted);
      block.parentNode.replaceChild(converted, block);
      return true;
    }

    var list = makeTag(kind);
    var li = makeTag('li');
    moveChildren(block, li);
    list.appendChild(li);
    block.parentNode.replaceChild(list, block);
    collapseInto(li);
    return true;
  }

  // ---- 列表层级：Tab 缩进 / Shift+Tab 降级 ------------------------
  function closestListItem(node) {
    var n = (node && node.nodeType === 1) ? node : (node ? node.parentNode : null);
    while (n && n !== document.body) {
      if (tagOf(n) === 'li') { return n; }
      n = n.parentNode;
    }
    return null;
  }

  // 前一项末尾的「同型子列表」（忽略纯空白文本节点），没有返回 null。
  // 序列化过的章节会在块级元素之间留换行，lastChild 未必是元素节点；
  // 但若末尾跟着的是**有内容**的文本，就不能认为子列表在末尾——那样新项会插到文字前面。
  function trailingSublist(li, kind) {
    var n = li.lastChild;
    while (n && n.nodeType !== 1) {
      if (n.nodeType === 3 && n.nodeValue.replace(/\s/g, '')) { return null; }
      n = n.previousSibling;
    }
    return (n && tagOf(n) === kind) ? n : null;
  }

  // Tab：把当前 li 挪进前一项里的同类型子列表（第一项没有前项，缩不了）。
  //
  // 前一项尾部**已有同型子列表时必须并入**，不能每次都新建：新建会并出两个平级列表
  // `<ul><li>甲<ul><li>乙</li></ul><ul><li>丙</li></ul></li></ul>`——丙 与乙 同级不同表，
  // 在 `<ol>` 下还会让序号从 1 重来；且该碎片里没有前一项，再按 Tab 缩不下去，
  // 表现出来就是「只能缩两层」（#67）。
  function indentListItem(li) {
    var list = li.parentNode;
    if (!list || (tagOf(list) !== 'ul' && tagOf(list) !== 'ol')) { return false; }
    var prev = li.previousSibling;
    while (prev && tagOf(prev) !== 'li') { prev = prev.previousSibling; }
    if (!prev) { return false; }
    var kind = tagOf(list);
    var sub = trailingSublist(prev, kind);
    if (!sub) {
      sub = makeTag(kind);
      prev.appendChild(sub);
    }
    sub.appendChild(li);
    collapseInto(li);
    return true;
  }

  // Shift+Tab：把当前 li（连同它后面的兄弟项）提出来放到宿主 li 之后；
  // 已经是顶层列表的项没有层级可降，维持原状。
  function outdentListItem(li) {
    var list = li.parentNode;
    if (!list || (tagOf(list) !== 'ul' && tagOf(list) !== 'ol')) { return false; }
    var hostLi = list.parentNode;
    if (tagOf(hostLi) !== 'li') { return false; }
    var moved = document.createDocumentFragment();
    var n = li;
    while (n) { var next = n.nextSibling; moved.appendChild(n); n = next; }
    hostLi.parentNode.insertBefore(moved, hostLi.nextSibling);
    if (!list.querySelector('li')) { list.parentNode.removeChild(list); }
    collapseInto(li);
    return true;
  }

  // 行内格式切换：光标/选区已在包裹层内则拆掉（含嵌套同名层），否则包上。
  // 拆掉后选区落在原被包裹内容上——用户可以直接再点一次重新包上。
  // 旧实现只会往上包：再点一次变成嵌套 <strong><strong>，永远取消不掉。
  function findWrap(node, tag) {
    var n = (node && node.nodeType === 1) ? node : (node ? node.parentNode : null);
    while (n && n !== document.body) {
      if (tagOf(n) === tag) { return n; }
      n = n.parentNode;
    }
    return null;
  }

  // 选区是否与 tag 元素相交——选区内只要含有该格式的文字就算命中
  function selectionTouches(range, tag) {
    var root = (range.commonAncestorContainer.nodeType === 1)
            ? range.commonAncestorContainer
            : range.commonAncestorContainer.parentNode;
    // 选区就落在某个 tag 元素内部（包裹应用后 selectContents 的形态：
    // commonAncestor 就是包裹本身，getElementsByTagName 在其内部找不到自身）
    for (var p = root; p && p !== document.body; p = p.parentNode) {
      if (tagOf(p) === tag) { return true; }
    }
    // 或选区横跨了包裹的边界，在共同祖先的子树里找相交的 tag 元素
    var els = root.getElementsByTagName(tag);
    for (var i = 0; i < els.length; i++) {
      if (range.intersectsNode(els[i])) { return true; }
    }
    return false;
  }

  function unwrapEl(el) {
    var parent = el.parentNode;
    if (!parent) { return null; }
    var first = el.firstChild;
    var last = el.lastChild;
    while (el.firstChild) { parent.insertBefore(el.firstChild, el); }
    var range = null;
    if (first) {
      range = document.createRange();
      range.setStartBefore(first);
      range.setEndAfter(last);
    }
    parent.removeChild(el);
    return range;
  }

  function toggleInline(tag) {
    var s = activeSelection();
    if (!s || !s.rangeCount) { return false; }
    var range = s.getRangeAt(0);
    if (!range.collapsed) {
      // 拖选：选区内含有该格式包裹就全部移除（与点亮语义严格对称——
      // 亮 ⇔ 选区相交 ⇔ 点击移除），一个都没有才包裹新标签。
      // 含锚点在包裹内的情形：锚点链上的包裹也在相交集合里，一并拆掉。
      return unwrapCovered(range, tag, s) || wrapInline(tag);
    }
    // 光标态：落在包裹内 = 取消该包裹（#54）；否则不做格式化
    //（避免空标签占位）。返回 true 的原因：false 会让 Java 侧
    // fallback 去往源码区插片段，把空标签换个地方再犯。
    var existing = findWrap(range.startContainer, tag);
    if (!existing) { return true; }
    var last = null;
    while (existing) {
      last = unwrapEl(existing);
      existing = last ? findWrap(last.startContainer, tag) : null;
    }
    if (last) { s.removeAllRanges(); s.addRange(last); }
    return true;
  }

  // 拆掉选区命中（祖先链上或子树内相交）的所有 tag 包裹，
  // 文档序逐个解开，保持选区落在最后解开处
  function unwrapCovered(range, tag, s) {
    var root = (range.commonAncestorContainer.nodeType === 1)
            ? range.commonAncestorContainer
            : range.commonAncestorContainer.parentNode;
    var targets = [];
    // 包裹应用后选区常锚定在包裹本身（commonAncestor 就是它），
    // 子树查找看不到它——祖先链要先并进来
    for (var p = root; p && p !== document.body; p = p.parentNode) {
      if (tagOf(p) === tag) { targets.push(p); }
    }
    var all = root.getElementsByTagName(tag);
    for (var i = 0; i < all.length; i++) {
      if (range.intersectsNode(all[i])) { targets.push(all[i]); }
    }
    var last = null;
    for (var j = 0; j < targets.length; j++) { last = unwrapEl(targets[j]); }
    if (last) { s.removeAllRanges(); s.addRange(last); }
    return targets.length > 0;
  }

  // 行内包裹：只在有实际选区时包起来（选区跨元素时退化为「抽出→包裹→放回」）。
  // 无选区的空标签占位（<em></em>）已废除——空行内标签对 XHTML 是纯噪音，
  // toggleInline 在光标态直接 no-op。
  function wrapInline(tag) {
    var s = activeSelection();
    if (!s) { return false; }
    var range = s.getRangeAt(0);
    var el = makeTag(tag);
    try {
      range.surroundContents(el);
    } catch (err) {
      el.appendChild(range.extractContents());
      range.insertNode(el);
    }
    selectContents(el);
    return true;
  }

  // ---- 受控内联样式：字体 / 字号 / 颜色 / 对齐（#72） --------------
  //
  // XHTML 里字体、字号、颜色、对齐都没有对应标签，只能落到内联 style 上。
  // 工具条**只管下面这四个属性**：writeStyle 按 STYLE_ORDER 统一重排它们，
  // 其余声明**原样保留**——正文里作者自己写的 text-indent / line-height / margin
  // 是货真价实的排版信息，改个对齐就把它抹掉是不可接受的损失。
  // （引擎私货与粘贴残留由 sanitize 在入口拦掉，走不到这里。）
  // 粘贴通道不受影响：sanitize 仍然把外来内容的内联样式全部剥掉——
  // 「作者导入的外来样式不进正文」与「作者自己选的样式进正文」是两件事。
  var INLINE_STYLE_PROPS = { 'font-family': 1, 'font-size': 1, 'color': 1 };
  var BLOCK_STYLE_PROPS = { 'text-align': 1 };
  // 固定输出顺序：同一处改多次字体/颜色后 style 串保持稳定，便于比对与断言
  var STYLE_ORDER = ['font-family', 'font-size', 'color', 'text-align'];

  function managedProp(name) {
    return !!(INLINE_STYLE_PROPS[name] || BLOCK_STYLE_PROPS[name]);
  }

  // 只读受管属性：工具条关心的就这四类，别的声明不该影响它的判断
  function readStyle(el) {
    var out = {};
    var raw = el.getAttribute('style') || '';
    var parts = raw.split(';');
    for (var i = 0; i < parts.length; i++) {
      var at = parts[i].indexOf(':');
      if (at <= 0) { continue; }
      var k = parts[i].slice(0, at).trim().toLowerCase();
      var v = parts[i].slice(at + 1).trim();
      if (v && managedProp(k)) { out[k] = v; }
    }
    return out;
  }

  // 把当前 style 串切成「受管 / 非受管」两拨。非受管那一拨按原顺序、原拼写带回去。
  function splitStyle(el) {
    var managed = [];
    var foreign = [];
    var raw = el.getAttribute('style') || '';
    var parts = raw.split(';');
    for (var i = 0; i < parts.length; i++) {
      var at = parts[i].indexOf(':');
      if (at <= 0) { continue; }
      var name = parts[i].slice(0, at).trim();
      var value = parts[i].slice(at + 1).trim();
      if (!value) { continue; }
      if (managedProp(name.toLowerCase())) { managed.push({ k: name.toLowerCase(), v: value }); }
      else { foreign.push(name + ': ' + value); }
    }
    return { managed: managed, foreign: foreign };
  }

  // 重建 style 串：受管的四类排在前（固定顺序），非受管声明原样跟在后面
  function writeStyle(el, props) {
    var owned = [];
    for (var i = 0; i < STYLE_ORDER.length; i++) {
      var k = STYLE_ORDER[i];
      if (props[k]) { owned.push(k + ': ' + props[k]); }
    }
    var all = owned.concat(splitStyle(el).foreign);
    if (all.length) { el.setAttribute('style', all.join('; ')); }
    else { el.removeAttribute('style'); }
  }

  // 除了受管四类之外还有别的声明吗？合并 style-only span 之前用它把关：
  // 内层一旦带着作者自己的声明，合并就会把它弄丢，宁可放弃合并（留着嵌套也无害）。
  function hasForeignStyle(el) {
    return splitStyle(el).foreign.length > 0;
  }

  // 置/清一个属性：value 为空串 = 清除（工具条上的「默认」档）。
  // 四类属性同处一条 style 串，所以必须「读出来改一处再整体写回」，
  // 不能直接 setAttribute 覆盖——那样改颜色会把同一处的字体设置冲掉。
  function setStyleProp(el, prop, value) {
    var props = readStyle(el);
    if (value) { props[prop] = value; } else { delete props[prop]; }
    writeStyle(el, props);
  }

  // 选区边界是否完整覆盖 el 的全部内容（用于「就地改已有 span」而不套新的）
  function fullyCovers(range, el) {
    var r = document.createRange();
    r.selectNodeContents(el);
    return range.compareBoundaryPoints(Range.START_TO_START, r) <= 0
            && range.compareBoundaryPoints(Range.END_TO_END, r) >= 0;
  }

  function inlineStyleHost(range) {
    var root = (range.commonAncestorContainer.nodeType === 1)
            ? range.commonAncestorContainer : range.commonAncestorContainer.parentNode;
    if (tagOf(root) === 'span' && fullyCovers(range, root)) { return root; }
    return null;
  }

  // 选区的两个端点是不是落在**同一个块**里。行内包裹只在此时才合法：
  // 一个 span 包住块级结构（span>p / span>ul）是非法 XHTML，回写进书结构就坏了。
  function spansOneBlock(range) {
    if (range.collapsed) { return true; }
    var from = closestBlock(range.startContainer) || topBlock(range.startContainer);
    var to = closestBlock(range.endContainer) || topBlock(range.endContainer);
    return !!from && from === to;
  }

  // 行内样式（字体 / 字号 / 颜色）：
  //   光标收起时落到所在块上——用户改字体/颜色常常不划选，落在块上才有反馈；
  //   有选区时按「选区整体」处理：先把选区内容取出来，在**片段**里剥掉同一属性，
  //   再包一层带新值的 span 放回去。**不是**在原文档上就地 surroundContents——
  //   选区端点常常切在既有样式元素中间（`前面<span 18px>正文内容</span>后面` 里
  //   用户只选「文内」），就地包围会失败并退化成「抽出 → 插入」，抽出的片段里带着
  //   **克隆的旧样式壳**，它嵌在新 span 的内层、旧值在内层胜出 —— 用户看到的就是
  //   「点了没反应」（#72 收尾）。extractContents 自己负责把切在中间的旧样式元素拆开，
  //   于是「只改选中的那一段」天然成立：未选中部分留在原元素上，选中部分得到新值。
  //   选区完整落在一个 span 里时仍然就地改它——「先设字体再设颜色」落在同一层。
  function applyInlineStyle(prop, value) {
    var s = activeSelection();
    if (!s || !s.rangeCount) { return false; }
    var range = s.getRangeAt(0);
    if (range.collapsed) {
      var block = topBlock(range.startContainer);
      if (block) { setStyleProp(block, prop, value); }
      return true;
    }
    if (!spansOneBlock(range)) {
      // 跨块选区：值落到选区触及的每个块上（与光标态同一落点），不产出包住段落的 span
      var touched = touchedBlocks(range);
      for (var i = 0; i < touched.length; i++) { setStyleProp(touched[i], prop, value); }
      return true;
    }
    var host = inlineStyleHost(range);
    if (host) { setStyleProp(host, prop, value); return true; }
    var frag = range.extractContents();
    if (hasBlockChild(frag)) {
      // 兜底：片段里带了块级元素（手写源码里的非法嵌套）。不套 span，原样放回，
      // 值落到块上——宁可粗一点，也不能把 span>p 写进正文
      insertAtPoint(frag, range);
      var fallback = touchedBlocks(range);
      for (var k = 0; k < fallback.length; k++) { setStyleProp(fallback[k], prop, value); }
      return true;
    }
    // 切在既有样式元素中间时先把它切开：不切的话放回去的内容仍落在它的「洞」里，
    // 旧值继续罩着新值（见 splitManagedAncestors / liftToEnd）
    splitManagedAncestors(range.startContainer, range.startOffset);
    // 片段内已有的同一属性声明全部清掉：值统一由外层新建的这一层提供，
    // 免得内层旧值在内侧胜出（这正是「同一样式再设一次无效」的另一半原因）
    var inner = frag.querySelectorAll('*');
    for (var j = 0; j < inner.length; j++) { setStyleProp(inner[j], prop, ''); }
    var el = makeTag('span');
    setStyleProp(el, prop, value);
    if (!el.getAttribute('style')) {
      // 清档（「默认」）：片段上的声明已剥掉，不再包新层——也不留下空包裹
      if (frag.firstChild) { insertAtPoint(frag, range); }
      return true;
    }
    el.appendChild(frag);
    insertAtPoint(el, range);
    selectContents(el);
    return true;
  }

  // 选区（或光标）触及的块：跨块选区取最外层命中块，避免父块已经设过、
  // 内部的 li / p 又各设一遍。
  function touchedBlocks(range) {
    var out = [];
    if (range.collapsed) {
      var one = closestBlock(range.startContainer) || topBlock(range.startContainer);
      if (one) { out.push(one); }
      return out;
    }
    var all = document.body.getElementsByTagName('*');
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (!BLOCK_NAMES[tagOf(el)] || !range.intersectsNode(el)) { continue; }
      var nested = false;
      for (var j = 0; j < out.length; j++) {
        if (out[j].contains(el)) { nested = true; break; }
      }
      if (!nested) { out.push(el); }
    }
    return out;
  }

  // 块级样式（对齐）：left 是默认值，写进正文纯属噪音 —— 直接清掉该声明。
  function applyBlockStyle(prop, value) {
    var s = activeSelection();
    if (!s || !s.rangeCount) { return false; }
    var blocks = touchedBlocks(s.getRangeAt(0));
    var effective = (prop === 'text-align' && value === 'left') ? '' : value;
    for (var i = 0; i < blocks.length; i++) {
      setStyleProp(blocks[i], prop, effective);
    }
    return true;
  }

  // ---- 字号放大 / 缩小：固定档位跳档（#72 二轮） ------------------
  //
  // 工具条上没有字号下拉框了，相对步进是唯一入口。用**固定档位表**而不是倍率：
  // 倍率会产出 16.8px → 20.16px 这种碎值，多按几次正文里就全是脏数字；
  // 跳档的结果永远是整齐值，作者能预期、我们也便于断言。
  var SIZE_STEPS = [10, 12, 14, 16, 18, 20, 24, 28, 32, 40, 48, 56, 72];

  // 锚点处**实际生效**的字号（px）。可能是内联的，也可能是从块 / 主题继承下来的，
  // 所以只能问计算样式——只看 style 属性会把「继承来的 16px」当成 0 或 undefined。
  function effectiveFontSizePx(node) {
    var el = (node && node.nodeType === 1) ? node : (node ? node.parentNode : null);
    if (!el || !window.getComputedStyle) { return 16; }
    var px = parseFloat(window.getComputedStyle(el).fontSize);
    return isNaN(px) ? 16 : px;
  }

  // 从光标处往上找**已经显式设过该属性**的最内层元素。步进要改这一处，而不是外层块：
  // 块上写了 20px、里面的 span 还挂着 18px 时改块会被 span 盖住，看起来像「按了没反应」。
  function styledAncestorAt(node, prop) {
    var el = (node && node.nodeType === 1) ? node : (node ? node.parentNode : null);
    while (el && el.nodeType === 1 && el.parentNode && el !== document.body) {
      if (readStyle(el)[prop]) { return el; }
      el = el.parentNode;
    }
    return null;
  }

  // 选区锚点最内层的元素（文本节点取父元素）。
  function anchorElement(range) {
    var node = range && range.startContainer;
    return (node && node.nodeType === 1) ? node : (node ? node.parentNode : null);
  }

  // 步进的落点。**读当前值与写新值必须落在同一处**——读 A 改 B 就会出现
  // 「档位算对了但画面没变化」。光标收起时优先就地改已设过字号的行内元素，
  // 否则改所在块（与字体 / 颜色的光标态一致）；有选区时复用 applyInlineStyle 的判据。
  function stepFontSizeTarget(range) {
    if (range.collapsed) {
      return styledAncestorAt(range.startContainer, 'font-size') || topBlock(range.startContainer);
    }
    return inlineStyleHost(range);
  }

  // 档位表里紧邻的一档：delta > 0 取第一个更大的，delta < 0 取最后一个更小的；
  // 越界返回 null（由调用方决定「有意不作为」）。
  function nextSizeStep(cur, delta) {
    var next = null;
    for (var i = 0; i < SIZE_STEPS.length; i++) {
      var step = SIZE_STEPS[i];
      if (delta > 0) {
        if (step > cur + 0.01) { return step; }
      } else if (step < cur - 0.01) {
        // 升序遍历里「最后一个仍小于当前值」的档就是上一档
        next = step;
      } else {
        break;
      }
    }
    return next;
  }

  // 放大 / 缩小一档。已经到顶或到底时**有意不作为**——但必须返回 true：
  // 返回 false 会让 Java 侧判定「命令被拒」而退回源码区插片段（#61 口径）。
  function stepFontSize(delta) {
    var s = activeSelection();
    if (!s || !s.rangeCount) { return false; }
    var range = s.getRangeAt(0);
    var host = stepFontSizeTarget(range);
    // 落点认不出（跨块选区且没有现成的行内 span）时，退回读锚点元素的字号
    var cur = effectiveFontSizePx(host || anchorElement(range));
    var next = nextSizeStep(cur, delta);
    if (next === null) { return true; }
    if (host) { setStyleProp(host, 'font-size', next + 'px'); return true; }
    // 没有现成落点 → 交给既有路径（包一层带 style 的 span 包住选区）
    return applyInlineStyle('font-size', next + 'px');
  }

  // ---- 格式清除（工具条「清除格式」） ------------------------------
  //
  // 把选区上**工具条管得着的格式**全部去掉，回到干净正文：
  //   · 行内声明 font-family / font-size / color；
  //   · 块级声明 text-align；
  //   · 强调类包裹 strong / em / u / del / code（含 b / i / s 这类别名写法）；
  //   · 块结构：标题 / 引用 / 列表项 → 普通段落。
  //
  // **不动**：`<a>`（链接是语义不是格式）、作者自己写的非受管声明（text-indent 这类
  // 排版信息）、`<img>` 等媒体、`<hr>` / `<pre>` / `<table>` 这类结构性元素、
  // 带 class / id 的作者包裹（拆了属性就跟着没了，只清声明不拆元素）。
  //
  // 粒度与工具条其它块级命令一致（对齐 / 段落 / 列表）：**按「选区触及的块」处理**。
  // 单块内的选区做「按选中内容」的精确清理；跨块选区逐块按整块处理（见 clearFormat）。
  var EMPHASIS_TAGS = { strong: 1, em: 1, u: 1, del: 1, code: 1, b: 1, i: 1, s: 1 };

  // 判定「这是不是行内元素」，只用来决定**切不切**。
  // 块级元素（段落 / 列表 / div / 表格单元格…）上的声明属于整块，切块（把一个段落
  // 劈成两段）不是清除格式该干的事；表外一律按行内处理（span / em / code / small…）。
  var BLOCKISH = { p: 1, h1: 1, h2: 1, h3: 1, h4: 1, h5: 1, h6: 1, ul: 1, ol: 1, li: 1,
                   blockquote: 1, pre: 1, div: 1, hr: 1, table: 1, thead: 1, tbody: 1,
                   tfoot: 1, tr: 1, td: 1, th: 1, caption: 1, dl: 1, dt: 1, dd: 1,
                   figure: 1, figcaption: 1, section: 1, article: 1, aside: 1, nav: 1,
                   header: 1, footer: 1, main: 1, form: 1, fieldset: 1, address: 1 };

  function isInlineElement(el) {
    var t = tagOf(el);
    return !!t && !BLOCKISH[t];
  }

  // 元素身上有没有受管的**行内**声明（字体 / 字号 / 颜色）。
  // 块级对齐写在块上，不参与「行内样式载体」的判定。
  function hasManagedInlineStyle(el) {
    var props = readStyle(el);
    for (var k in INLINE_STYLE_PROPS) { if (props[k]) { return true; } }
    return false;
  }

  // 清掉元素上的受管行内声明，其余声明（含作者自己的 text-indent 等）原样保留。
  // 声明全清空时 writeStyle 会把 style 属性整个摘掉。
  function stripManagedInline(el) {
    var props = readStyle(el);
    for (var k in INLINE_STYLE_PROPS) { delete props[k]; }
    writeStyle(el, props);
  }

  // 把 el 在 (node, offset) 处一分为二：切点之后的内容移进一个克隆（插到 el 之后），
  // 克隆保留 el 的属性（style / class…），于是切完两半的原样式都还在。
  // 深层切点交给 extractContents 处理（它会克隆路径上的中间元素）——手写这套拆分
  // 很容易在文本节点 / 嵌套元素上出错，让 DOM 自己算才可靠。
  function splitElementAt(el, node, offset) {
    if (!el || !el.parentNode) { return null; }
    var left = document.createRange();
    left.setStart(el, 0);
    left.setEnd(node, offset);
    if (left.collapsed) { return null; }            // 切点就在 el 开头，不用切
    var right = document.createRange();
    right.setStart(node, offset);
    right.setEnd(el, el.childNodes.length);
    if (right.collapsed) { return null; }           // 切点在 el 末尾，不用切
    var clone = el.cloneNode(false);
    clone.appendChild(right.extractContents());
    el.parentNode.insertBefore(clone, el.nextSibling);
    return clone;
  }

  // 在（折叠的）切点上把带受管行内样式的**行内**祖先一分为二。
  // 清除格式必须做这一步：选区端点常常切在旧样式元素中间，不切的话，取出来再放回去的
  // 内容仍落在那个元素的「洞」里，照样继承旧值——用户看到「清不掉」。
  // 链上从内到外逐个切：切内层不会动到外层（内容的前半段始终留在原位），
  // 所以同一个 (node, offset) 对每一层都成立。
  // 切开之后那半段（克隆）插在原元素之后，落点仍是「原元素的内部末尾」——
  // 所以放回内容必须走 insertAtPoint（liftToEnd 会把落点提到克隆之前）。
  function splitManagedAncestors(node, offset) {
    var el = (node && node.nodeType === 1) ? node : (node ? node.parentNode : null);
    var chain = [];
    for (var n = el; n && n !== document.body; n = n.parentNode) {
      if (isInlineElement(n) && hasManagedInlineStyle(n)) { chain.push(n); }
    }
    for (var i = 0; i < chain.length; i++) { splitElementAt(chain[i], node, offset); }
  }

  // 裸的强调类包裹直接拆掉（keep text）。带 class / id 的是作者自己的语义，
  // 拆了属性就跟着没了——那种只清声明、保留元素。
  function unwrapEmphasisIfBare(el) {
    if (EMPHASIS_TAGS[tagOf(el)] && styleOnly(el) && el.parentNode) { unwrapEl(el); }
  }

  // 选区取出来的那一小片：清受管声明 + 拆强调包裹。
  // 只在片段上跑，所以碰不到未选中的部分——这正是「拆分」想要的粒度。
  // querySelectorAll 是**静态**快照（getElementsByTagName 是实时的，边拆边遍历会漏元素）。
  function cleanFragment(frag) {
    var els = frag.querySelectorAll('*');
    for (var i = 0; i < els.length; i++) { stripManagedInline(els[i]); }
    for (var j = 0; j < els.length; j++) { unwrapEmphasisIfBare(els[j]); }
  }

  // li → p：摘出列表，作为段落落在列表之后（与 formatBlock('p') 对列表项的处理同一口径）。
  // li 里的块级孩子（Tab 缩进的子列表等）不能进段落——摘出来跟在后面。
  function liToParagraph(li) {
    var list = li.parentNode;
    if (!list || !isList(list)) { return null; }
    var p = makeTag('p');
    // 同 resetBlockFormat：作者在 li 上写的非受管声明跟着搬进新段落
    var foreign = splitStyle(li).foreign;
    if (foreign.length) { p.setAttribute('style', foreign.join('; ')); }
    var blocks = pullOutBlocks(li);
    moveChildren(li, p);
    list.parentNode.insertBefore(p, list.nextSibling);
    appendBlocksAfter(blocks, p);
    list.removeChild(li);
    if (!list.querySelector('li')) { list.parentNode.removeChild(list); }
    return p;
  }

  // node 在父节点里的位置 + 1（=「node 之后」那个折点的 offset）
  function indexAfter(node) {
    var at = 0;
    for (var c = node.previousSibling; c; c = c.previousSibling) { at++; }
    return at + 1;
  }

  // 折点落在带样式的**行内**元素末尾时，把落点逐层提到它外面。
  //
  // 为什么必须提：`(span, span.childNodes.length)` 与 `(span.parentNode, indexOf(span)+1)`
  // 是同一个 DOM 位置，但 insertNode 的语义完全不同——前者把内容塞进 span **里面**
  // （照样吃它的样式），后者才落在它外面。清除格式要求内容必须在样式元素外面，否则
  // 用户看到「清不掉」；「同一样式再设一次」也会因此退化成嵌套壳（#72 收尾）。
  // 只对行内元素提：块上的声明属于整块，内容不该被搬出段落。
  // 折点也可能落在**文本节点**的末尾（抽取正文中段时最典型）：`(text, text.length)`
  // 与「父元素里 text 之后的那个折点」是同一个 DOM 位置，但 insertNode 会把内容塞进
  // 父元素**里面**。safari 系的实现里 extractContents 之后收起的 Range 正是这种形态，
  // 所以只认元素节点会让「选中间几个字」这条路径整条漏掉（清除/改字号都清不掉）。
  // 每一层都要求「折点恰好在末尾」才是**同一个位置**的等价改写；中途不是末尾就停——
  // 那种折点是真的在行内元素内部，搬出去会把内容移过后面的兄弟节点（位置就变了）。
  function liftToEnd(node, offset) {
    var n = node;
    var at = offset;
    while (n) {
      var parent = n.parentNode;
      if (!parent || parent.nodeType !== 1) { break; }
      var atEnd = (n.nodeType === 1) ? (at === n.childNodes.length) : (at === n.nodeValue.length);
      if (!atEnd) { break; }
      if (n.nodeType === 1) {
        // 元素：前提是它本身是行内容器（块上的声明属于整块，内容不该被搬出段落）
        if (!isInlineElement(n)) { break; }
      } else if (!isInlineElement(parent)) {
        // 文本节点直接躺在块里（<p>文字|</p>）：折点本来就不在任何行内容器内部，不必提
        break;
      }
      // 提到父节点里「紧跟 n 之后」那个折点——与 (n, 末尾) 是同一个位置，
      // 但 insertNode 的语义从「n 里面」变成「n 外面」。注意下一步要走到 **parent**：
      // 元素节点上写成 n 自己会原地打转，offset 被多加一格 → setStart 越界。
      at = indexAfter(n);
      n = parent;
    }
    return { node: n, offset: at };
  }

  // 把片段插到（折叠的）折点上，落点先经 liftToEnd 提到样式元素外面
  function insertAtPoint(frag, range) {
    var at = liftToEnd(range.startContainer, range.startOffset);
    var box = document.createRange();
    box.setStart(at.node, at.offset);
    box.collapse(true);
    box.insertNode(frag);
  }

  // 选区端点直接落在**节点自身**（而不是父节点的索引上）：随后的块结构替换会把子节点
  // 搬进新块里，端点在父节点上的 Range 会随父节点一起失效，落在节点上的不会。
  function selectBetween(first, last) {
    var s = activeSelection();
    if (!s || !first || !last) { return; }
    var r = document.createRange();
    r.setStart(first, 0);
    r.setEnd(last, last.nodeType === 3 ? last.nodeValue.length : last.childNodes.length);
    s.removeAllRanges();
    s.addRange(r);
  }

  // 选区**整块**覆盖的块：只有这种块才连它自己声明的样式一起清。
  // 部分覆盖的块不动它的声明——那些声明作用于整块，清掉会连累没选中的文字。
  function fullyCoveredBlocks(range, blocks) {
    var out = [];
    for (var i = 0; i < blocks.length; i++) {
      if (fullyCovers(range, blocks[i])) { out.push(blocks[i]); }
    }
    return out;
  }

  // 块结构退回普通段落 + 清掉块级受管声明（对齐）。
  // 列表 → 每个 li 各转一段；标题 / 引用 → p；段落 / 分隔线 / 代码块 / 表格 / div
  // 是**结构**不是格式，原样保留。返回处理后的块（被替换时是新元素），无法处理返回 null。
  function resetBlockFormat(block) {
    if (!block || !block.parentNode) { return null; }
    setStyleProp(block, 'text-align', '');
    var t = tagOf(block);
    if (isList(block)) { return listToBlocks(block) || null; }
    if (t === 'li') { return liToParagraph(block); }
    if (t === 'p' || t === 'hr' || t === 'pre' || t === 'table' || t === 'div') { return block; }
    var fresh = makeTag('p');
    // 作者自己的非受管声明（text-indent 这类排版信息）跟着块一起搬过去——
    // 换标签不是丢排版信息的理由。受管的四类不搬：对齐在上面已清掉，
    // 行内三类由调用方按「整块是否都在选区里」决定清不清。
    var foreign = splitStyle(block).foreign;
    if (foreign.length) { fresh.setAttribute('style', foreign.join('; ')); }
    var moved = pullOutBlocks(block);
    moveChildren(block, fresh);
    block.parentNode.replaceChild(fresh, block);
    appendBlocksAfter(moved, fresh);
    // 块里只有块级孩子（引用套段落这种）时新段落是空壳——空段落是噪音，不留
    if (isEmptyParagraph(fresh)) {
      fresh.parentNode.removeChild(fresh);
      return moved.length ? moved[0] : null;
    }
    return fresh;
  }

  // 「块结构退回段落」的落点 = 选区触及的**格式块**：引用 / 标题 / 列表项。
  //
  // 刻意不把 `<ul>` / `<ol>` 当目标：整列表退回段落会把**没选中的列表项**一起转掉
  // （用户选了十项里的一项，十项全变成段落）。列表项本身才是格式块，选中哪几项就退哪几项，
  // 与「段落」按钮对列表项的处理同一口径（formatBlock 把 li 摘出来变成一个 p）。
  // 段落 / div 不是格式，不进目标；引用块里套的段落也不必进来——引用块整体换成段落时，
  // 里面的块级孩子会被摘出来跟在后面，结构照样合法。
  var FORMAT_BLOCKS = 'blockquote,h1,h2,h3,h4,h5,h6,li';

  function structureTargets(range) {
    var out = [];
    if (range.collapsed) {
      var one = closestBlock(range.startContainer) || topBlock(range.startContainer);
      if (one) { out.push(one); }
      return out;
    }
    var els = document.body.querySelectorAll(FORMAT_BLOCKS);
    for (var i = 0; i < els.length; i++) {
      if (range.intersectsNode(els[i])) { out.push(els[i]); }
    }
    return out;
  }

  // 选区范围内（原文档上）逐个元素清：受管行内声明 + 裸的强调包裹。
  // 用于跨块选区——那种选区不做「取出 → 放回」（块级结构来回搬会错位）。
  // 注意粒度：跨块选区按**块粒度**清理，边界上被切到的元素会连未选中部分一起去掉声明；
  // 单块选区不走这条路，那里是按选中内容精确清的（见 clearFormat）。
  function stripFormatWithin(range) {
    var els = document.body.querySelectorAll('*');
    for (var i = 0; i < els.length; i++) {
      if (!range.intersectsNode(els[i])) { continue; }
      stripManagedInline(els[i]);
      unwrapEmphasisIfBare(els[i]);
    }
  }

  function clearFormat() {
    var s = activeSelection();
    if (!s || !s.rangeCount) { return false; }
    var range = s.getRangeAt(0);
    // 块结构稍后要整体替换（重置返回的是新元素），先把落点定格下来
    var structures = structureTargets(range);

    if (range.collapsed) {
      // 光标态：没有「选中的一段」可分，清理落在光标链上的每一层
      //（强调包裹 + 受管声明），块结构与对齐落到光标所在的块。
      // 链要先收成数组：拆掉一层会让它的 parentNode 变成 null，边走边拆会当场断在半路，
      // 块上的声明就漏掉了（光标在 <p style=…><strong>x</strong></p> 里时踩实过）。
      var chain = [];
      var el = (range.startContainer.nodeType === 1)
              ? range.startContainer : range.startContainer.parentNode;
      for (var n = el; n && n !== document.body; n = n.parentNode) { chain.push(n); }
      for (var i = 0; i < chain.length; i++) {
        if (chain[i].nodeType !== 1) { continue; }
        stripManagedInline(chain[i]);
        unwrapEmphasisIfBare(chain[i]);
      }
      for (var b = 0; b < structures.length; b++) {
        var after = resetBlockFormat(structures[b]);
        // 块被换成新元素时原 Range 落在已脱离文档的旧块上，把光标挪进新块
        if (after && after !== structures[b]) { collapseInto(after); }
      }
      return true;
    }

    if (!spansOneBlock(range)) {
      stripFormatWithin(range);
      for (var c = 0; c < structures.length; c++) { resetBlockFormat(structures[c]); }
      return true;
    }

    var full = fullyCoveredBlocks(range, touchedBlocks(range));
    // 整块都在选区里的块：块**自己**的受管行内声明也清掉（只清它不影响没选中的文字）。
    // 必须在块结构替换之前做——替换之后旧元素已脱离文档，再清它等于没清。
    for (var e = 0; e < full.length; e++) { stripManagedInline(full[e]); }
    var frag = range.extractContents();
    // 取出来之后只剩一个折点：此时把带受管样式的行内祖先切开，选中的内容才可能脱离
    // 旧样式（见 splitManagedAncestors）。不切的话放回去的内容仍落在那个元素的「洞」里。
    splitManagedAncestors(range.startContainer, range.startOffset);
    cleanFragment(frag);
    var first = frag.firstChild;
    var last = frag.lastChild;
    if (first) { insertAtPoint(frag, range); }
    // 块结构退回段落放在内容放回**之后**：替换块元素会把子节点搬进新块，
    // 先放回、后替换，内容才跟着一起搬过去
    for (var d = 0; d < structures.length; d++) { resetBlockFormat(structures[d]); }
    if (first) { selectBetween(first, last); }
    return true;
  }

  // ---- 命令表（工具条 / 快捷键共用） ------------------------------
  window.epubraFormat = function (kind, value) {
    var ok = false;
    if (kind === 'paragraph') { ok = formatBlock('p'); }
    else if (kind === 'heading') { ok = formatBlock('h2'); }
    else if (kind === 'quote') { ok = toggleQuote(); }
    else if (kind === 'list') { ok = toggleList('ul'); }
    else if (kind === 'ol') { ok = toggleList('ol'); }
    else if (kind === 'rule') { ok = insertRule(); }
    else if (kind === 'bold') { ok = toggleInline('strong'); }
    else if (kind === 'italic') { ok = toggleInline('em'); }
    else if (kind === 'underline') { ok = toggleInline('u'); }
    else if (kind === 'strike') { ok = toggleInline('del'); }
    else if (kind === 'code') { ok = toggleInline('code'); }
    else if (kind === 'link') { ok = wrapLink(value); }
    else if (kind === 'unlink') { ok = unwrapLink(); }
    else if (kind === 'font') { ok = applyInlineStyle('font-family', value); }
    // size = 指定字号（内部 API，测试与将来的字号输入框用）；size-up / size-down = 工具条按钮的跳档
    else if (kind === 'size') { ok = applyInlineStyle('font-size', value); }
    else if (kind === 'size-up') { ok = stepFontSize(1); }
    else if (kind === 'size-down') { ok = stepFontSize(-1); }
    else if (kind === 'color') { ok = applyInlineStyle('color', value); }
    else if (kind === 'align') { ok = applyBlockStyle('text-align', value); }
    // 清除格式：一次清掉受控四类声明 + 强调类包裹 + 块结构（详见 clearFormat）
    else if (kind === 'clear') { ok = clearFormat(); }
    if (ok) { push(); }
    return ok;
  };

  // 引用：与段落互转（再点一次退回段落）
  function toggleQuote() {
    var s = activeSelection();
    if (!s) { return false; }
    var block = topBlock(s.getRangeAt(0).startContainer);
    if (!block) { return false; }
    return formatBlock(tagOf(block) === 'blockquote' ? 'p' : 'blockquote');
  }

  // 分隔线：插在当前块之后，并在它下面补一个空段落让光标有落点
  function insertRule() {
    var s = activeSelection();
    if (!s) { return false; }
    var block = topBlock(s.getRangeAt(0).startContainer);
    if (!block) { return false; }
    var p = makeTag('p');
    block.parentNode.insertBefore(makeTag('hr'), block.nextSibling);
    block.parentNode.insertBefore(p, block.nextSibling.nextSibling);
    collapseInto(p);
    return true;
  }

  // 链接：选区包成 <a href>；空选区插一对空 <a> 并把光标落在中间。
  // 光标已在链接内时（epubraQueryLink 返回非空），wrapLink 不再嵌套，
  // 直接改写现有链接的 href——「编辑链接」语义。
  function wrapLink(href) {
    var safe = safeUrl(href, false);
    if (!safe) { return false; }
    var s = activeSelection();
    if (!s || !s.rangeCount) { return false; }
    var range = s.getRangeAt(0);
    var existing = findWrap(range.startContainer, 'a');
    if (existing) {
      existing.setAttribute('href', safe);
      return true;
    }
    var el = makeTag('a');
    el.setAttribute('href', safe);
    if (range.collapsed) {
      // 无选区不插空 <a href="…"></a>（链接没锚文本就没意义）；返回 true
      // 同 toggleInline：避免 Java 侧 fallback 去源码区插片段
      return true;
    }
    try {
      range.surroundContents(el);
    } catch (err) {
      el.appendChild(range.extractContents());
      range.insertNode(el);
    }
    selectContents(el);
    return true;
  }

  // 取消链接：拆掉光标/选区所在的 <a>，保留里面的文字
  function unwrapLink() {
    var s = activeSelection();
    if (!s || !s.rangeCount) { return false; }
    var wrapped = findWrap(s.getRangeAt(0).startContainer, 'a');
    if (!wrapped) { return false; }
    var last = null;
    while (wrapped) {
      last = unwrapEl(wrapped);
      wrapped = last ? findWrap(last.startContainer, 'a') : null;
    }
    if (last) { s.removeAllRanges(); s.addRange(last); }
    return true;
  }

  // ---- 光标状态查询：驱动工具条的「按下」态 -----------------------
  var INLINE_FMT = { strong: 'bold', em: 'italic', u: 'underline',
                     del: 'strike', code: 'code', a: 'link' };
  var BLOCK_NAMES = { p: 1, h1: 1, h2: 1, h3: 1, h4: 1, h5: 1, h6: 1,
                      li: 1, blockquote: 1, pre: 1, div: 1 };

  function closestBlock(node) {
    var n = (node && node.nodeType === 1) ? node : (node ? node.parentNode : null);
    while (n && n !== document.body) {
      if (BLOCK_NAMES[tagOf(n)]) { return n; }
      n = n.parentNode;
    }
    return null;
  }

  window.epubraQuery = function () {
    var s = activeSelection();
    if (!s) { return ''; }
    var range = s.getRangeAt(0);
    var out = [];
    if (range.collapsed) {
      var n = (range.startContainer.nodeType === 1)
              ? range.startContainer : range.startContainer.parentNode;
      while (n && n !== document.body) {
        var name = INLINE_FMT[tagOf(n)];
        if (name && out.indexOf(name) < 0) { out.push(name); }
        n = n.parentNode;
      }
    } else {
      // 拖选点亮语义：选区内只要含有该格式的文字（相交）就点亮对应按钮，
      // 点击由 toggleInline 移除选区内的该格式包裹——亮 ⇔ 可移除，严格对称。
      for (var t in INLINE_FMT) {
        if (selectionTouches(range, t) && out.indexOf(INLINE_FMT[t]) < 0) {
          out.push(INLINE_FMT[t]);
        }
      }
    }
    var block = closestBlock(range.startContainer);
    var bt = tagOf(block);
    if (bt === 'li') {
      // 无序列表报 'list'（点「列表」按钮退回段落），有序列表报 'ol'
      var ln = block.parentNode;
      out.push(ln && tagOf(ln) === 'ol' ? 'ol' : 'list');
    }
    else if (bt === 'h1' || bt === 'h2' || bt === 'h3') { out.push('heading'); }
    else if (bt === 'blockquote') { out.push('quote'); }
    else if (bt === 'p') { out.push('paragraph'); }
    return out.join(' ');
  };

  // 光标所在链接的 href；不在链接里返回空串（供「插入链接」弹窗回填地址）
  window.epubraQueryLink = function () {
    var s = activeSelection();
    if (!s || !s.rangeCount) { return ''; }
    var found = findWrap(s.getRangeAt(0).startContainer, 'a');
    return found ? (found.getAttribute('href') || '') : '';
  };

  // 光标 / 选区起点处生效的内联样式，格式为「属性=值」以分号相连（如
  // "font-size=18px;text-align=center"）。供工具条的字体、字号、颜色、对齐全下拉/色板回显。
  // 四类属性的取值都是我们自己的受控字面量（字体名不含分号，颜色是 #rrggbb），
  // 所以不需要做转义。内层 span 先读到，再往外补块级声明，到最近的块为止。
  window.epubraQueryStyle = function () {
    var s = activeSelection();
    if (!s || !s.rangeCount) { return ''; }
    var range = s.getRangeAt(0);
    var found = {};
    var n = (range.startContainer.nodeType === 1)
            ? range.startContainer : range.startContainer.parentNode;
    while (n && n !== document.body) {
      var props = readStyle(n);
      for (var i = 0; i < STYLE_ORDER.length; i++) {
        var k = STYLE_ORDER[i];
        if (props[k] && !found[k]) { found[k] = props[k]; }
      }
      if (BLOCK_NAMES[tagOf(n)]) { break; }
      n = n.parentNode;
    }
    var buf = [];
    for (var j = 0; j < STYLE_ORDER.length; j++) {
      if (found[STYLE_ORDER[j]]) { buf.push(STYLE_ORDER[j] + '=' + found[STYLE_ORDER[j]]); }
    }
    return buf.join(';');
  };

  function notifySelection() {
    var b = window.epubraBridge;
    if (!b || !b.onSelectionChanged) { return; }
    b.onSelectionChanged(window.epubraQuery());
    // 桥桩（测试里的 stub）可能没实现样式上报，缺了就只报格式名
    if (b.onStyleChanged) { b.onStyleChanged(window.epubraQueryStyle()); }
  }
  document.addEventListener('selectionchange', notifySelection);
  document.addEventListener('keyup', notifySelection);
  document.addEventListener('mouseup', notifySelection);

  // ---- 快捷键 ----------------------------------------------------
  // 用捕获阶段：编辑器的默认处理在目标元素上，冒泡阶段拦不住。
  // Ctrl+Z / Ctrl+Y 交给 Java 的应用级快照撤销，避免两套撤销栈打架。

  // 取按键标识。**JavaFX WebView 里不能信 e.key / e.code**：真实按键映射下来两者
  // 恒为空串（实测 Tab → key=[] code=[] keyCode=9；Ctrl+B → key=[] code=[] keyCode=66），
  // 于是 `(e.key || '') === 'Tab'` 恒为假 —— Tab 缩进与 Ctrl+B/I/U 全部静默失效，
  // 而且不 preventDefault 会让 Tab 的默认行为把焦点带出编辑器（#68）。
  // 可靠的只有 keyCode / which，以及修饰键标志（ctrlKey / shiftKey / altKey）。
  // 字母键的 keyCode 恒为**大写** ASCII（按住 Shift 也不变），故统一转小写。
  function keyToken(e) {
    if (e.key) { return String(e.key); }
    var code = e.keyCode || e.which || 0;
    if (code === 9) { return 'Tab'; }
    if (code === 13) { return 'Enter'; }
    if (code === 27) { return 'Escape'; }
    if (code >= 65 && code <= 90) { return String.fromCharCode(code + 32); }
    if (code >= 48 && code <= 57) { return String.fromCharCode(code); }
    return '';
  }

  document.addEventListener('keydown', function (e) {
    // 编辑器内**一律吞掉 Tab**（产品决策）：Tab 永远属于编辑器内容，不把焦点带走。
    //   · 列表里 → 多层级缩进；Shift+Tab → 降级；
    //   · 不在列表里 → 有意不作为，但**照样拦掉默认行为**。
    // 不拦的话 JavaFX 的焦点遍历会把焦点送出编辑器（#68 的用户可见症状）。
    // 键盘用户要跳出编辑器走 Ctrl+Tab —— 带修饰键不进这一段。
    if (keyToken(e) === 'Tab' && !(e.ctrlKey || e.metaKey)) {
      var s = activeSelection();
      var li = (s && s.rangeCount) ? closestListItem(s.getRangeAt(0).startContainer) : null;
      if (li) {
        var done = e.shiftKey ? outdentListItem(li) : indentListItem(li);
        if (done) { push(); }
      }
      e.preventDefault();
      e.stopPropagation();
      return;
    }
    if (!(e.ctrlKey || e.metaKey)) { return; }
    var k = keyToken(e).toLowerCase();
    var handled = true;
    if (k === 'b') { window.epubraFormat('bold'); }
    else if (k === 'i') { window.epubraFormat('italic'); }
    else if (k === 'u') { window.epubraFormat('underline'); }
    else if (k === 'z' && !e.shiftKey) { bridgeCall('onUndo'); }
    else if (k === 'y' || (k === 'z' && e.shiftKey)) { bridgeCall('onRedo'); }
    else { handled = false; }
    if (handled) { e.preventDefault(); e.stopPropagation(); }
  }, true);

  function bridgeCall(name) {
    if (window.epubraBridge && window.epubraBridge[name]) { window.epubraBridge[name](); }
  }

  // ---- 粘贴净化 --------------------------------------------------
  // 外部富文本带着任意标签 / 内联样式进来，直接落进正文会让产出的
  // EPUB 带上非法结构。这里只放行白名单标签，其余拆掉标签保留文字。
  var ALLOWED = { p: 1, h1: 1, h2: 1, h3: 1, h4: 1, h5: 1, h6: 1,
                  ul: 1, ol: 1, li: 1, blockquote: 1, pre: 1, hr: 1, br: 1,
                  strong: 1, em: 1, u: 1, del: 1, code: 1, a: 1, img: 1 };
  var ALIAS = { b: 'strong', i: 'em', strike: 'del', s: 'del', ins: 'u' };
  var KEEP_ATTRS = { a: ['href'], img: ['src', 'alt'] };
  var DROPPED = { script: 1, style: 1, head: 1, meta: 1, link: 1, title: 1,
                  iframe: 1, object: 1, embed: 1, form: 1, input: 1, svg: 1 };

  // 挡掉 javascript: / vbscript:；data: 只允许出现在 img 上
  function safeUrl(value, allowData) {
    if (!value) { return null; }
    var probe = value.replace(/[\u0000-\u0020]/g, '').toLowerCase();
    if (probe.indexOf('javascript:') === 0) { return null; }
    if (probe.indexOf('vbscript:') === 0) { return null; }
    if (!allowData && probe.indexOf('data:') === 0) { return null; }
    return value;
  }

  function copySanitized(from, to) {
    var kids = from.childNodes;
    for (var i = 0; i < kids.length; i++) {
      var n = kids[i];
      if (n.nodeType === 3) {
        to.appendChild(document.createTextNode(n.nodeValue));
        continue;
      }
      if (n.nodeType !== 1) { continue; }
      var name = n.tagName.toLowerCase();
      if (DROPPED[name]) { continue; }
      if (!ALLOWED[name] && !ALIAS[name]) { copySanitized(n, to); continue; }
      var mapped = ALIAS[name] ? ALIAS[name] : name;
      var el = makeTag(mapped);
      var keep = KEEP_ATTRS[mapped] || [];
      for (var j = 0; j < keep.length; j++) {
        var raw = n.getAttribute(keep[j]);
        var ok = safeUrl(raw, mapped === 'img');
        if (ok) { el.setAttribute(keep[j], ok); }
      }
      copySanitized(n, el);
      to.appendChild(el);
    }
  }

  function sanitize(html) {
    var out = document.createDocumentFragment();
    var parsed = new DOMParser().parseFromString(html, 'text/html');
    if (!parsed || !parsed.body) { return out; }
    copySanitized(parsed.body, out);
    return out;
  }

  // 供测试与 Java 侧复用：返回净化结果的序列化文本
  window.epubraSanitize = function (html) {
    var box = makeTag('div');
    box.appendChild(sanitize(html));
    return new XMLSerializer().serializeToString(box);
  };

  document.addEventListener('paste', function (e) {
    var dt = e.clipboardData;
    if (!dt) { return; }
    e.preventDefault();
    e.stopPropagation();
    var html = dt.getData('text/html');
    var text = dt.getData('text/plain');
    if (html) { insertFragment(sanitize(html)); }
    else { insertPlainText(text); }
  }, true);

  function insertPlainText(text) {
    if (!text) { return; }
    var lines = text.split('\n');
    if (lines.length === 1) {
      var only = document.createDocumentFragment();
      only.appendChild(document.createTextNode(text));
      insertFragment(only);
      return;
    }
    var frag = document.createDocumentFragment();
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i].replace('\r', '');
      if (!line.length) { continue; }
      var p = makeTag('p');
      p.appendChild(document.createTextNode(line));
      frag.appendChild(p);
    }
    insertFragment(frag);
  }

  // 段落类容器：<p> 不能作为它们的子元素。p>p 是非法嵌套，回写进书的 XHTML 结构会损坏，
  // 再次加载后内容展示异常（同 formatBlock 对 p>ul / p>p 的处理口径）。
  var PARAGRAPH_LIKE = { p: 1, h1: 1, h2: 1, h3: 1, h4: 1, h5: 1, h6: 1,
                         pre: 1, dt: 1, caption: 1, address: 1 };

  // 某个父容器要装 <p> 时需要的包裹标签：列表项 / 定义列表项只能用它们各自的子元素。
  // 其余容器（body / div / li / td / blockquote / section…）都能直接容纳 <p>，返回空串。
  function blockHostFor(parent) {
    var t = tagOf(parent);
    if (t === 'ul' || t === 'ol') { return 'li'; }
    if (t === 'dl') { return 'dd'; }
    return '';
  }

  // 「空段落」：没有可见内容。contenteditable 的空行常留一个 <br/> 占位，不算内容。
  // 只认 <p>——空标题 / 空列表项是用户有意的结构，不动。
  function isEmptyParagraph(el) {
    if (tagOf(el) !== 'p') { return false; }
    if ((el.textContent || '').replace(/\s/g, '').length) { return false; }
    return !el.querySelector('img,hr,video,audio,iframe,object,svg,math');
  }

  function collapseAfterSelection(s, node) {
    if (!s || !node || !node.parentNode) { return; }
    var r = document.createRange();
    r.setStartAfter(node);
    r.collapse(true);
    s.removeAllRanges();
    s.addRange(r);
  }

  // 片段里是否已有块级元素（Java 侧 joinInsertFragments 产出的是 <p>…</p> 串）。
  // 没有就由这里替调用方补一层 <p>——否则一个裸 <img/> 会直接挂在 body 下，
  // 既不是段落也不受段落样式约束。
  function hasBlockChild(frag) {
    for (var c = frag.firstChild; c; c = c.nextSibling) {
      if (c.nodeType !== 1) { continue; }
      var t = tagOf(c);
      if (isBlockTag(t) || PARAGRAPH_LIKE[t] || t === 'dl' || t === 'figure') { return true; }
    }
    return false;
  }

  // 把片段整段落成「独立段落」，返回是否成功。
  //
  // 老实现直接 range.insertNode(frag)，片段落在光标处——图片会紧贴光标前的文字挤在同一行，
  // 用户看到的就是「图片没有自己的行」。新口径把片段挪到<b>光标所在段落之后</b>：
  //   · 片段本身没有块级元素 → 先包一层 <p>，保证落进文档的是一个块；
  //   · 光标不在任何已知块里（表格单元格、body 直属行内元素…）→ 按老行为落在光标处；
  //   · 光标所在块能容纳 <p>（div / li / td / blockquote…）→ 也按老行为落在光标处，合法；
  //   · 光标所在块是段落类（p / h1~h6 / pre…）→ 整段插到它<b>后面</b>，避免 p>p；
  //     若它本身就是个空行占位，插入后被顶替掉（点了空行插图，图片正好落在这一行）。
  function insertAsOwnBlock(frag) {
    var s = activeSelection();
    if (!s || !frag) { return false; }
    if (!hasBlockChild(frag)) { frag = wrapInTag('p', frag); }
    var range = s.getRangeAt(0);
    var block = closestBlock(range.startContainer);
    if (!block || !PARAGRAPH_LIKE[tagOf(block)]) {
      return insertFragment(frag);
    }
    var parent = block.parentNode;
    if (!parent) { return insertFragment(frag); }
    var host = blockHostFor(parent);
    var placed = host ? wrapInTag(host, frag) : frag;
    // ⚠ 先取引用：appendChild / insertBefore 会把 DocumentFragment 的子节点移交出去，
    // frag 随即变空，之后再取 lastChild 恒为 null（#57 踩实）
    var anchor = placed.lastChild;
    parent.insertBefore(placed, block.nextSibling);
    if (isEmptyParagraph(block)) { parent.removeChild(block); }
    collapseAfterSelection(s, anchor);
    push();
    notifySelection();
    return true;
  }

  function wrapInTag(tag, frag) {
    var box = makeTag(tag);
    box.appendChild(frag);
    return box;
  }

  // 把一段 XHTML 片段插到光标处（图片等），成功返回 true
  //
  // 返回 false 会让 Java 侧退回源码区再插一次（切 tab 时 flush 会把章节冲掉），
  // 所以「排版策略不成立」不能返回 false——那种情况一律退回内联插入。
  // ---- 查找 / 替换（可视化编辑器内） ------------------------------
  //
  // 查找条原来只作用在源码区 TextArea：可视化页签上点「查找」其实在隐藏的源码区里
  // 选中了，页面上毫无可见变化（用户看到「查找没有生效」）。这里把同一套语义
  // （正向 / 反向 + 回绕 + 区分大小写）搬到 DOM 上：正文展开成纯文本做索引推进，
  // 命中后映射回文本节点，用 Range 选中并滚到可见；替换改动经 push() 走既有回写。
  //
  // 关键词 / 替换词经 window 临时成员传入（与格式命令同口径），不拼进脚本字符串。

  // 正文全部文本节点 + 各自的纯文本起点：索引推进在纯文本上做，命中再映射回来
  function buildTextMap() {
    var nodes = [];
    var text = '';
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
    var n;
    while ((n = walker.nextNode())) {
      if (!n.nodeValue) { continue; }
      nodes.push({ node: n, start: text.length });
      text += n.nodeValue;
    }
    return { nodes: nodes, text: text };
  }

  // 纯文本全局索引 → (文本节点, 节点内偏移)。二分找「起点 <= index 的最后一个」条目
  function locateInMap(map, index) {
    var lo = 0;
    var hi = map.nodes.length - 1;
    while (lo < hi) {
      var mid = (lo + hi + 1) >> 1;
      if (map.nodes[mid].start <= index) { lo = mid; } else { hi = mid - 1; }
    }
    var entry = map.nodes[lo];
    return { node: entry.node, offset: index - entry.start };
  }

  // (node, offset) 折点 → 纯文本全局索引。文本节点直接按登记序号；
  // 元素上的折点取「第一个不早于折点的文本节点」的开头（正文末尾折点返回总长）。
  function globalIndexAt(node, offset, map) {
    if (node && node.nodeType === 3) {
      for (var i = 0; i < map.nodes.length; i++) {
        if (map.nodes[i].node === node) { return map.nodes[i].start + offset; }
      }
    }
    if (!node) { return 0; }
    if (offset > node.childNodes.length) { offset = node.childNodes.length; }
    var probe = document.createRange();
    probe.setStart(node, offset);
    probe.collapse(true);
    for (var j = 0; j < map.nodes.length; j++) {
      if (probe.comparePoint(map.nodes[j].node, 0) >= 0) { return map.nodes[j].start; }
    }
    return map.text.length;
  }

  function indexOfCs(text, kw, from, cs) {
    if (from > text.length) { from = text.length; }
    if (from < 0) { from = 0; }
    return cs ? text.indexOf(kw, from) : text.toLowerCase().indexOf(kw.toLowerCase(), from);
  }

  // 与源码区 TextSearch.lastIndexOf 同口径：from 是匹配**起点**允许的最大索引
  function lastIndexOfCs(text, kw, from, cs) {
    if (from > text.length - 1) { from = text.length - 1; }
    if (from < 0) { return -1; }
    return cs ? text.lastIndexOf(kw, from) : text.toLowerCase().lastIndexOf(kw.toLowerCase(), from);
  }

  // 当前选区在纯文本里的推进起点：正向从选区末尾（跳过当前命中），反向从选区开头 - 1
  function selectionIndex(map, backward) {
    var s = activeSelection();
    if (!s || !s.rangeCount) { return backward ? map.text.length - 1 : 0; }
    var r = s.getRangeAt(0);
    if (backward) {
      return globalIndexAt(r.startContainer, r.startOffset, map) - 1;
    }
    return globalIndexAt(r.endContainer, r.endOffset, map);
  }

  // 选中命中并滚到可见。注意不能走 activeSelection()——它要求 rangeCount>=1，
  // 而「从未点进正文 / 查找条刚打开」时选区是空的（rangeCount==0），那正是第一次
  // 查找的场景；window.getSelection() 本身此时依然有效，addRange 照常工作。
  function selectHit(map, idx, len) {
    var a = locateInMap(map, idx);
    var b = locateInMap(map, idx + len);
    var r = document.createRange();
    r.setStart(a.node, a.offset);
    r.setEnd(b.node, b.offset);
    var s = window.getSelection();
    if (!s) { return; }
    s.removeAllRanges();
    s.addRange(r);
    var el = (b.node.nodeType === 1) ? b.node : b.node.parentNode;
    if (el && el.scrollIntoView) {
      try { el.scrollIntoView({ block: 'center' }); } catch (err) { el.scrollIntoView(); }
    }
  }

  // 返回 "hit"（直接命中）/ "wrap"（回绕命中）/ "miss"（未找到）
  window.epubraFindNext = function (kw, cs) {
    if (!kw) { return 'miss'; }
    var map = buildTextMap();
    var from = selectionIndex(map, false);
    var idx = indexOfCs(map.text, kw, from, cs);
    var wrapped = false;
    if (idx < 0) { idx = indexOfCs(map.text, kw, 0, cs); wrapped = idx >= 0; }
    if (idx < 0) { return 'miss'; }
    selectHit(map, idx, kw.length);
    return wrapped ? 'wrap' : 'hit';
  };

  window.epubraFindPrev = function (kw, cs) {
    if (!kw) { return 'miss'; }
    var map = buildTextMap();
    var from = selectionIndex(map, true);
    var idx = lastIndexOfCs(map.text, kw, from, cs);
    var wrapped = false;
    if (idx < 0) { idx = lastIndexOfCs(map.text, kw, map.text.length - 1, cs); wrapped = idx >= 0; }
    if (idx < 0) { return 'miss'; }
    selectHit(map, idx, kw.length);
    return wrapped ? 'wrap' : 'hit';
  };

  // 替换当前选中的一处：选区文本与关键词一致才动手（与源码区 replaceOne 同口径）。
  // 返回 "hit" / "nomatch"（当前选区不是命中）/ "miss"（没有选区）。
  window.epubraReplaceOne = function (kw, replacement, cs) {
    if (!kw) { return 'miss'; }
    var s = activeSelection();
    if (!s || !s.rangeCount) { return 'miss'; }
    var r = s.getRangeAt(0);
    var selected = r.toString();
    var hit = cs ? (selected === kw) : (selected.toLowerCase() === kw.toLowerCase());
    if (!selected || !hit) { return 'nomatch'; }
    r.deleteContents();
    var t = document.createTextNode(replacement);
    r.insertNode(t);
    var after = document.createRange();
    after.setStart(t, 0);
    after.setEnd(t, replacement.length);
    s.removeAllRanges();
    s.addRange(after);
    push();
    return 'hit';
  };

  // 替换本章全部命中，返回命中数。命中可跨行内元素（如「第一<b>段</b>」里的「第一段」）：
  // 定位与替换都走 Range，从最后一个命中往前替换，前面的索引不受影响。
  window.epubraReplaceAll = function (kw, replacement, cs) {
    if (!kw) { return 0; }
    var map = buildTextMap();
    var haystack = cs ? map.text : map.text.toLowerCase();
    var needle = cs ? kw : kw.toLowerCase();
    var hits = [];
    var at = haystack.indexOf(needle);
    while (at >= 0) {
      hits.push(at);
      at = haystack.indexOf(needle, at + needle.length);
    }
    if (!hits.length) { return 0; }
    for (var i = hits.length - 1; i >= 0; i--) {
      var a = locateInMap(map, hits[i]);
      var b = locateInMap(map, hits[i] + kw.length);
      var r = document.createRange();
      r.setStart(a.node, a.offset);
      r.setEnd(b.node, b.offset);
      r.deleteContents();
      r.insertNode(document.createTextNode(replacement));
    }
    push();
    return hits.length;
  };

  window.epubraInsertHtml = function (html) {
    if (!html) { return false; }
    var s = activeSelection();
    if (!s) { return false; }
    try {
      return insertAsOwnBlock(s.getRangeAt(0).createContextualFragment(html));
    } catch (err) {
      return false;
    }
  };

  // 公共插入：片段落在光标处，光标移到片段之后
  function insertFragment(frag) {
    var s = activeSelection();
    if (!s || !frag) { return false; }
    var range = s.getRangeAt(0);
    var last = frag.lastChild;
    range.deleteContents();
    range.insertNode(frag);
    if (last) {
      var after = document.createRange();
      after.setStartAfter(last);
      after.collapse(true);
      s.removeAllRanges();
      s.addRange(after);
    }
    push();
    notifySelection();
    return true;
  }
})();