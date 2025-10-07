export const INJECT_STYLE = `
(function(){
  const H = "llm-correct-highlight";
  const SID = "llm-correct-style";
  if (!document.getElementById(SID)) {
    const style = document.createElement("style");
    style.id = SID;
    style.textContent = "." + H + "{background:rgba(34,197,94,.25)!important;border-radius:6px;transition:background .25s}";
    document.head.appendChild(style);
  }
})();
`;

export const EXTRACT_QUESTIONS = `
(function(){
  function escapeSelector(value){
    if (typeof value !== "string") return "";
    if (window.CSS && typeof window.CSS.escape === "function") {
      return window.CSS.escape(value);
    }
    return value.replace(/[^a-zA-Z0-9_-]/g, function(char){
      return "\\\\" + char;
    });
  }

  function cssPath(element){
    if (!(element instanceof Element)) return null;
    const path = [];
    let current = element;
    while (current && current.nodeType === 1 && path.length < 6) {
      let selector = current.nodeName.toLowerCase();
      if (current.id) {
        selector += "#" + escapeSelector(current.id);
        path.unshift(selector);
        break;
      }
      let sibling = current;
      let nth = 1;
      while ((sibling = sibling.previousElementSibling)) {
        if (sibling.nodeName === current.nodeName) nth++;
      }
      selector += ":nth-of-type(" + nth + ")";
      path.unshift(selector);
      current = current.parentElement;
    }
    return path.join(" > ");
  }

  const form = document.querySelector('form,[role="form"],[data-quiz],.quiz,.question-container') || document.body;
  const groups = new Map();
  form.querySelectorAll('input[type="radio"],input[type="checkbox"]').forEach((input, idx) => {
    const groupKey = input.name || input.closest('fieldset') || "__g" + idx;
    const label = form.querySelector('label[for="' + input.id + '"]') || input.closest('label');
    const questionNode = input.closest('fieldset,.question,.question-block,.quiz-question') || form;
    const legend = questionNode.querySelector('legend,.question-title,h3,h4,.title');
    if (!groups.has(groupKey)) {
      groups.set(groupKey, {
        questionText: (legend?.innerText || "").trim(),
        options: []
      });
    }
    groups.get(groupKey).options.push({
      text: (label?.innerText || input.value || "").trim(),
      selector: input.id ? "#" + escapeSelector(input.id) : cssPath(input),
      labelSelector: label ? cssPath(label) : null
    });
  });

  return JSON.stringify({
    url: location.href,
    title: document.title,
    questions: Array.from(groups.values())
  });
})();
`;

export const PAINT_HIGHLIGHTS = (answersJson: string) => `
(function(){
  try {
    const H = "llm-correct-highlight";
    const answers = ${answersJson};
    (answers || []).forEach(answer => {
      const target = (answer.selector && document.querySelector(answer.selector))
        || (answer.labelSelector && document.querySelector(answer.labelSelector));
      const node = answer.labelSelector ? target : (target?.closest('label') || target);
      if (node) node.classList.add(H);
    });
    return "OK";
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return "ERR:" + message;
  }
})();
`;