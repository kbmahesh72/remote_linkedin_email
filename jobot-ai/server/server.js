import express from 'express'
import multer from 'multer'
import { existsSync } from 'node:fs'
import { appendFile, mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'
import { inflateRawSync } from 'node:zlib'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const appRoot = path.resolve(__dirname, '..')
const projectRoot = path.resolve(appRoot, '..')
const automationOutputDir = path.join(projectRoot, 'automation-output')
const dataDir = path.join(appRoot, 'data')
const resumeDir = path.join(dataDir, 'resumes')
const csvPath = path.join(dataDir, 'subscriptions.csv')

const app = express()
const upload = multer({
  storage: multer.memoryStorage(),
  limits: {
    fileSize: 8 * 1024 * 1024,
  },
})

const csvHeaders = [
  'createdAt',
  'fullName',
  'email',
  'phone',
  'linkedinUrl',
  'targetRole',
  'location',
  'keyword',
  'jobType',
  'frequency',
  'subject',
  'subjectVariants',
  'body',
  'signature',
  'resumeFileName',
  'consent',
]

app.post('/api/subscriptions', upload.single('resume'), async (request, response) => {
  try {
    await mkdir(resumeDir, { recursive: true })

    const payload = normalizePayload(request.body)
    const missing = requiredFields().filter((field) => !payload[field])
    if (missing.length > 0) {
      return response.status(400).json({ message: `Missing required fields: ${missing.join(', ')}` })
    }
    if (payload.consent !== 'true') {
      return response.status(400).json({ message: 'Consent is required.' })
    }
    if (!request.file) {
      return response.status(400).json({ message: 'Resume attachment is required.' })
    }

    const extension = path.extname(request.file.originalname) || '.resume'
    const resumeFileName = `${emailFileStem(payload.email)}${extension.toLowerCase()}`
    await writeFile(path.join(resumeDir, resumeFileName), request.file.buffer)

    const row = {
      createdAt: new Date().toISOString(),
      ...payload,
      resumeFileName,
    }

    await ensureCsv()
    await appendFile(csvPath, `${csvHeaders.map((header) => csvValue(row[header])).join(',')}\n`, 'utf8')

    response.json({
      message: 'Subscription saved successfully.',
      csvPath,
      resumeFileName,
    })
  } catch (error) {
    console.error(error)
    response.status(500).json({ message: 'Could not save subscription.' })
  }
})

app.get('/api/todays-recruiters', async (request, response) => {
  try {
    const workbookPath = path.join(automationOutputDir, `${todayFileStem()}.xlsx`)
    if (!existsSync(workbookPath)) {
      return response.json({
        date: todayFileStem(),
        workbookPath,
        recruiters: [],
      })
    }

    response.json({
      date: todayFileStem(),
      workbookPath,
      recruiters: await readRecruitersFromWorkbook(workbookPath),
    })
  } catch (error) {
    console.error(error)
    response.status(500).json({ message: 'Could not read today recruiter emails.' })
  }
})

const port = Number(process.env.PORT || 4174)
app.listen(port, '127.0.0.1', () => {
  console.log(`Subscription API listening on http://127.0.0.1:${port}`)
})

function normalizePayload(body) {
  return {
    fullName: clean(body.fullName),
    email: clean(body.email).toLowerCase(),
    phone: clean(body.phone),
    linkedinUrl: clean(body.linkedinUrl),
    targetRole: clean(body.targetRole),
    location: clean(body.location),
    keyword: clean(body.keyword),
    jobType: clean(body.jobType),
    frequency: clean(body.frequency),
    subject: clean(body.subject),
    subjectVariants: clean(body.subjectVariants),
    body: clean(body.body),
    signature: clean(body.signature),
    consent: clean(body.consent) === 'true' ? 'true' : 'false',
  }
}

function clean(value) {
  return String(value ?? '').trim()
}

function requiredFields() {
  return [
    'fullName',
    'email',
    'phone',
    'linkedinUrl',
    'targetRole',
    'location',
    'keyword',
    'jobType',
    'frequency',
    'subject',
    'subjectVariants',
    'body',
    'signature',
  ]
}

async function ensureCsv() {
  await mkdir(dataDir, { recursive: true })
  if (!existsSync(csvPath)) {
    await writeFile(csvPath, `${csvHeaders.join(',')}\n`, 'utf8')
  }
}

function csvValue(value) {
  const text = String(value ?? '')
  return `"${text.replaceAll('"', '""')}"`
}

function emailFileStem(email) {
  return email.toLowerCase().replace(/[^a-z0-9@._-]/g, '_')
}

function todayFileStem() {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}${month}${day}`
}

async function readRecruitersFromWorkbook(workbookPath) {
  const zipEntries = readZipEntries(await readFile(workbookPath))
  const sharedStrings = readSharedStrings(zipEntries.get('xl/sharedStrings.xml')?.toString('utf8') ?? '')
  const sheetXml = zipEntries.get('xl/worksheets/sheet1.xml')?.toString('utf8') ?? ''
  const rows = readWorksheetRows(sheetXml, sharedStrings)
  if (rows.length < 2) {
    return []
  }

  const headers = rows[0].map((value) => value.toLowerCase())
  const emailIndex = headers.indexOf('email')
  const timestampIndex = headers.indexOf('timestamp')
  if (emailIndex < 0) {
    return []
  }

  return rows
    .slice(1)
    .map((row) => ({
      email: clean(row[emailIndex]),
      timestamp: timestampIndex >= 0 ? clean(row[timestampIndex]) : '',
    }))
    .filter((item) => item.email)
}

function readZipEntries(buffer) {
  const entries = new Map()
  const endOffset = findEndOfCentralDirectory(buffer)
  if (endOffset < 0) {
    return entries
  }

  const entryCount = buffer.readUInt16LE(endOffset + 10)
  const centralDirectoryOffset = buffer.readUInt32LE(endOffset + 16)
  let offset = centralDirectoryOffset
  for (let index = 0; index < entryCount; index += 1) {
    if (buffer.readUInt32LE(offset) !== 0x02014b50) {
      break
    }
    const compressionMethod = buffer.readUInt16LE(offset + 10)
    const compressedSize = buffer.readUInt32LE(offset + 20)
    const fileNameLength = buffer.readUInt16LE(offset + 28)
    const extraLength = buffer.readUInt16LE(offset + 30)
    const commentLength = buffer.readUInt16LE(offset + 32)
    const localHeaderOffset = buffer.readUInt32LE(offset + 42)
    const fileName = buffer.toString('utf8', offset + 46, offset + 46 + fileNameLength)
    const localNameLength = buffer.readUInt16LE(localHeaderOffset + 26)
    const localExtraLength = buffer.readUInt16LE(localHeaderOffset + 28)
    const dataStart = localHeaderOffset + 30 + localNameLength + localExtraLength
    const data = buffer.subarray(dataStart, dataStart + compressedSize)

    if (compressionMethod === 0) {
      entries.set(fileName, data)
    } else if (compressionMethod === 8) {
      entries.set(fileName, inflateRawSync(data))
    }

    offset += 46 + fileNameLength + extraLength + commentLength
  }
  return entries
}

function findEndOfCentralDirectory(buffer) {
  for (let offset = buffer.length - 22; offset >= 0; offset -= 1) {
    if (buffer.readUInt32LE(offset) === 0x06054b50) {
      return offset
    }
  }
  return -1
}

function readSharedStrings(xml) {
  return [...xml.matchAll(/<si[^>]*>([\s\S]*?)<\/si>/g)].map((match) =>
    decodeXml([...match[1].matchAll(/<t[^>]*>([\s\S]*?)<\/t>/g)].map((textMatch) => textMatch[1]).join('')),
  )
}

function readWorksheetRows(xml, sharedStrings) {
  return [...xml.matchAll(/<row[^>]*>([\s\S]*?)<\/row>/g)].map((rowMatch) => {
    const row = []
    for (const cellMatch of rowMatch[1].matchAll(/<c\b([^>]*)>([\s\S]*?)<\/c>/g)) {
      const attributes = cellMatch[1]
      const body = cellMatch[2]
      const columnIndex = columnIndexFromRef((attributes.match(/\br="([A-Z]+)\d+"/)?.[1] ?? 'A'))
      row[columnIndex] = readCellValue(attributes, body, sharedStrings)
    }
    return row.map((value) => value ?? '')
  })
}

function readCellValue(attributes, body, sharedStrings) {
  const type = attributes.match(/\bt="([^"]+)"/)?.[1] ?? ''
  if (type === 'inlineStr') {
    return decodeXml([...body.matchAll(/<t[^>]*>([\s\S]*?)<\/t>/g)].map((match) => match[1]).join(''))
  }
  const rawValue = body.match(/<v[^>]*>([\s\S]*?)<\/v>/)?.[1] ?? ''
  if (type === 's') {
    return sharedStrings[Number(rawValue)] ?? ''
  }
  return decodeXml(rawValue)
}

function columnIndexFromRef(ref) {
  return [...ref].reduce((total, letter) => total * 26 + letter.charCodeAt(0) - 64, 0) - 1
}

function decodeXml(value) {
  return String(value ?? '')
    .replaceAll('&lt;', '<')
    .replaceAll('&gt;', '>')
    .replaceAll('&quot;', '"')
    .replaceAll('&apos;', "'")
    .replaceAll('&amp;', '&')
}
