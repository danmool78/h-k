import asyncio, sys, certifi
certifi.where = lambda: '/root/.ccr/ca-bundle.crt'
import edge_tts
async def main(text, out, voice, rate):
    await edge_tts.Communicate(text, voice, rate=rate).save(out)
asyncio.run(main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else 'ko-KR-SunHiNeural', sys.argv[4] if len(sys.argv) > 4 else '+0%'))
