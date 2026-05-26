import json
path = r'C:\Users\mufan\.cursor\projects\d-code-manus-ai-agent\agent-transcripts\b8861ab3-74a6-46c1-b6ed-484eb8a8b00f\b8861ab3-74a6-46c1-b6ed-484eb8a8b00f.jsonl'
with open(path, encoding='utf-8') as f:
    for i, line in enumerate(f):
        if i == 70:
            d = json.loads(line)
            txt = d['message']['content'][0]['text']
            start = txt.find('<div id="userPostedFeeds"')
            if start >= 0:
                end_marker = '<!--]--><!--[--><!--]--></div>'
                end = txt.find(end_marker, start)
                if end >= 0:
                    end += len(end_marker)
                else:
                    end = txt.rfind('</section>', start, start + 200000) + 10
                    end = txt.find('</div>', end) + 6
                html = txt[start:end]
                with open('feeds_html.html', 'w', encoding='utf-8') as out:
                    out.write(html)
                print('Saved', len(html), 'chars')
            break
