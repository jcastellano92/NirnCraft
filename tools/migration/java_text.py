"""Small balanced-expression scanner used by explicit, reviewable migration transforms."""
def matching(text,start):
    pairs={'(':')','{':'}','[':']'};stack=[pairs[text[start]]];i=start+1
    while i<len(text):
        if text.startswith('//',i):
            end=text.find('\n',i+2);i=len(text) if end<0 else end+1;continue
        if text.startswith('/*',i):
            end=text.find('*/',i+2)
            if end<0:raise ValueError('Unterminated comment')
            i=end+2;continue
        if text.startswith('"""',i):
            end=text.find('"""',i+3)
            if end<0:raise ValueError('Unterminated text block')
            i=end+3;continue
        if text[i] in ('"',"'"):
            quote=text[i];i+=1
            while i<len(text):
                if text[i]=='\\':i+=2;continue
                if text[i]==quote:i+=1;break
                i+=1
            continue
        c=text[i]
        if c in pairs:stack.append(pairs[c])
        elif c==stack[-1]:
            stack.pop()
            if not stack:return i
        i+=1
    raise ValueError('Unterminated expression at '+str(start))

def arguments(text):
    parts=[];start=0;i=0
    while i<len(text):
        if text[i] in '([{':i=matching(text,i)+1;continue
        if text[i] in ('"',"'"):
            q=text[i];i+=1
            while i<len(text):
                if text[i]=='\\':i+=2;continue
                if text[i]==q:i+=1;break
                i+=1
            continue
        if text[i]==',':parts.append(text[start:i]);start=i+1
        i+=1
    parts.append(text[start:]);return parts
