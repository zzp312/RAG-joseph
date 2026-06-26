import os
import sys
import json
import logging
from pathlib import Path
from typing import List, Dict, Any

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

def count_tokens(string: str, encoding_name: str = "cl100k_base") -> int:
    try:
        import tiktoken
        encoding = tiktoken.get_encoding(encoding_name)
        tokens = encoding.encode(string)
        return len(tokens)
    except ImportError:
        logger.warning("tiktoken not installed, using character count as fallback")
        return len(string)

def split_text(text: str, chunk_size: int = 300, chunk_overlap: int = 50) -> List[str]:
    chunks = []
    text_length = len(text)
    
    if text_length <= chunk_size:
        return [text]
    
    start = 0
    while start < text_length:
        end = min(start + chunk_size, text_length)
        
        if end < text_length:
            last_period = text.rfind('.', start, end)
            last_newline = text.rfind('\n', start, end)
            last_space = text.rfind(' ', start, end)
            
            best_split = max(last_period, last_newline, last_space)
            
            if best_split > start + chunk_overlap:
                end = best_split + 1
            else:
                end = min(start + chunk_size, text_length)
        
        chunk = text[start:end].strip()
        if chunk:
            chunks.append(chunk)
        
        start = end - chunk_overlap
        if start < 0:
            start = 0
    
    return chunks

def split_document(parsed_data: Dict[str, Any], chunk_size: int = 300, chunk_overlap: int = 50) -> Dict[str, Any]:
    text = parsed_data.get('text', '')
    
    if not text:
        logger.warning("No text content to split")
        parsed_data['chunks'] = []
        return parsed_data
    
    text_chunks = split_text(text, chunk_size, chunk_overlap)
    
    chunks_with_meta = []
    chunk_id = 0
    
    for chunk in text_chunks:
        chunks_with_meta.append({
            'id': chunk_id,
            'type': 'content',
            'text': chunk,
            'length_tokens': count_tokens(chunk),
            'page': 1
        })
        chunk_id += 1
    
    tables = parsed_data.get('tables', [])
    if tables:
        for table in tables:
            table_text = table.get('markdown', '') if isinstance(table, dict) else str(table)
            if table_text:
                chunks_with_meta.append({
                    'id': chunk_id,
                    'type': 'table',
                    'text': table_text,
                    'length_tokens': count_tokens(table_text),
                    'page': table.get('page', 1) if isinstance(table, dict) else 1,
                    'table_id': table.get('table_id', chunk_id) if isinstance(table, dict) else chunk_id
                })
                chunk_id += 1
    
    parsed_data['chunks'] = chunks_with_meta
    parsed_data['total_chunks'] = len(chunks_with_meta)
    
    return parsed_data

def split_from_file(input_file: str, output_file: str = None, chunk_size: int = 300, chunk_overlap: int = 50) -> Dict[str, Any]:
    try:
        with open(input_file, 'r', encoding='utf-8') as f:
            parsed_data = json.load(f)
    except Exception as e:
        logger.error(f"Error reading input file: {e}")
        return {"error": f"Error reading input file: {str(e)}"}
    
    result = split_document(parsed_data, chunk_size, chunk_overlap)
    
    if output_file:
        try:
            with open(output_file, 'w', encoding='utf-8') as f:
                json.dump(result, f, indent=2, ensure_ascii=False)
            logger.info(f"Split result saved to: {output_file}")
        except Exception as e:
            logger.error(f"Error writing output file: {e}")
    
    return result

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(json.dumps({"error": "Please provide input file path as argument"}, ensure_ascii=False))
        sys.exit(1)
    
    input_file = sys.argv[1]
    output_file = sys.argv[2] if len(sys.argv) > 2 else None
    chunk_size = int(sys.argv[3]) if len(sys.argv) > 3 else 300
    chunk_overlap = int(sys.argv[4]) if len(sys.argv) > 4 else 50
    
    result = split_from_file(input_file, output_file, chunk_size, chunk_overlap)
    print(json.dumps(result, ensure_ascii=False))
