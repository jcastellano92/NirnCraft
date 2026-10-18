"""Evidence checks matched to the actual native server APIs/logs, without booting it."""
from pathlib import Path
import re
import zipfile


def dimension_ids(core_or_resources):
    source = Path(core_or_resources)
    if source.is_dir():
        names = [path.relative_to(source).as_posix() for path in (source / 'data').glob('*/dimension/**/*.json')]
    else:
        with zipfile.ZipFile(source) as archive:
            names = archive.namelist()
    # The normal world's overworld comes from its world preset; Skycraft ships
    # explicit Nether/End replacements and its other dimensions as JSON.
    dimensions = {'minecraft:overworld'}
    for name in names:
        match = re.fullmatch(r'data/([^/]+)/dimension/(.+)[.]json', name)
        if match:
            dimensions.add(match[1] + ':' + match[2])
    if not {'minecraft:overworld', 'minecraft:the_nether', 'minecraft:the_end'}.issubset(dimensions):
        raise ValueError('The full native dimension definitions are incomplete')
    return dimensions


def shutdown_evidence(text, expected):
    # 26.3 no longer writes the old generic "All dimensions are saved" line.
    # It logs every level save, returns through the native stop path and closes FML.
    start = text.rfind('Stopping server')
    if start < 0:
        raise ValueError('Missing native server stop evidence')
    stop = text[start:]
    saved = set(re.findall(r"Saving chunks for level '[^']*'/([a-z0-9_.-]+:[a-z0-9_./-]+)", stop))
    missing = set(expected) - saved
    if missing:
        raise ValueError('Missing native shutdown saves for dimensions: ' + ', '.join(sorted(missing)))
    if 'Saving worlds' not in stop or 'Closing FML Loader' not in stop or 'Clearing ModLoader' not in stop:
        raise ValueError('Native world saving/loader shutdown did not complete')
    if re.search(r'/(?:ERROR|FATAL)\]|Exception in thread', stop):
        raise ValueError('Native shutdown reported a failure')
    return {'expectedDimensions': sorted(expected), 'savedDimensions': sorted(saved), 'nativeLoaderClosed': True}


def command_failure(line):
    return bool(re.search(r'/(?:ERROR|FATAL)\]|Exception in thread|Bake problems:|Unknown or incomplete command|'
                          r"Can't find element|<--\[HERE\]", line))
