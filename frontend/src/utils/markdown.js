import MarkdownIt from 'markdown-it'

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  typographer: false
})

export function renderMarkdown(text) {
  if (!text) {
    return '<p class="muted">暂无参考答案</p>'
  }
  return md.render(text)
}
