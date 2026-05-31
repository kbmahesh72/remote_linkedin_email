import { useEffect, useMemo, useState } from 'react'
import QRCode from 'qrcode'
import {
  ArrowRight,
  BellRing,
  BriefcaseBusiness,
  Check,
  Clock3,
  FileText,
  Mail,
  MessageCircle,
  Phone,
  Search,
  Send,
  ShieldCheck,
  Sparkles,
  Upload,
  UserRound,
} from 'lucide-react'
import './App.css'

const tabs = [
  { id: 'overview', label: 'Tool', icon: Sparkles },
  { id: 'subscribe', label: 'Configure', icon: FileText },
  { id: 'contact', label: 'Contact', icon: MessageCircle },
]

const jobTypes = ['C2C', 'Full-time', 'Contract', 'Remote', 'Hybrid']
const frequencies = ['Instant', 'Every 15 min', 'Hourly', 'Daily digest']

function App() {
  const [activeTab, setActiveTab] = useState('overview')
  const [saveState, setSaveState] = useState({ status: 'idle', message: '' })
  const [paymentQr, setPaymentQr] = useState('')
  const [form, setForm] = useState({
    fullName: '',
    email: '',
    phone: '',
    linkedinUrl: '',
    targetRole: '',
    location: '',
    keyword: 'Java c2c hiring',
    jobType: 'C2C',
    frequency: 'Instant',
    subject: '',
    subjectVariants: '',
    body: '',
    signature: '',
    resumeName: '',
    resumeFile: null,
    consent: true,
  })

  const completion = useMemo(() => {
    const requiredFields = [
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
      'resumeName',
      'consent',
    ]
    const filled = requiredFields.filter((field) => {
      if (field === 'consent') {
        return form.consent
      }
      return String(form[field]).trim()
    }).length
    return Math.round((filled / requiredFields.length) * 100)
  }, [form])

  function updateField(event) {
    const { name, type, checked, value, files } = event.target
    setForm((current) => ({
      ...current,
      ...(files
        ? { resumeName: files[0]?.name ?? '', resumeFile: files[0] ?? null }
        : { [name]: type === 'checkbox' ? checked : value }),
    }))
    setSaveState({ status: 'idle', message: '' })
    setPaymentQr('')
  }

  async function saveSubscription(event) {
    event.preventDefault()
    setSaveState({ status: 'saving', message: 'Saving subscription request...' })

    const formData = new FormData()
    Object.entries(form).forEach(([key, value]) => {
      if (key !== 'resumeFile' && key !== 'resumeName') {
        formData.append(key, String(value ?? ''))
      }
    })
    if (form.resumeFile) {
      formData.append('resume', form.resumeFile)
    }

    try {
      const response = await fetch('/api/subscriptions', {
        method: 'POST',
        body: formData,
      })
      const result = await response.json()
      if (!response.ok) {
        throw new Error(result.message || 'Could not save subscription.')
      }
      setSaveState({
        status: 'saved',
        message: `Saved to CSV. Resume stored as ${result.resumeFileName}.`,
      })
      setPaymentQr(await createRandomPaymentQr(form.email))
    } catch (error) {
      setSaveState({ status: 'error', message: error.message })
      setPaymentQr('')
    }
  }

  return (
    <main className="app-shell">
      <section className="brand-band">
        <div className="brand-lockup">
          <div className="logo-mark" aria-hidden="true">
            <BellRing size={26} />
          </div>
          <div>
            <p className="eyebrow">Jobot AI</p>
            <h1>Hit Recruiters' Inbox First</h1>
          </div>
        </div>

        <div className="signal-strip" aria-label="Automation highlights">
          <span>
            <Clock3 size={16} /> 1-minute discovery
          </span>
          <span>
            <Mail size={16} /> Hit Recruiters Inbox in a minute after Job post
          </span>
          <span>
            <ShieldCheck size={16} /> Resume-ready outreach
          </span>
        </div>
      </section>

      <nav className="tabs" aria-label="Subscription setup tabs">
        {tabs.map((tab) => {
          const Icon = tab.icon
          return (
            <button
              key={tab.id}
              type="button"
              className={activeTab === tab.id ? 'tab active' : 'tab'}
              onClick={() => setActiveTab(tab.id)}
              aria-pressed={activeTab === tab.id}
            >
              <Icon size={18} />
              {tab.label}
            </button>
          )
        })}
      </nav>

      {activeTab === 'overview' && <OverviewTab onStart={() => setActiveTab('subscribe')} />}
      {activeTab === 'subscribe' && (
        <SubscribeTab
          form={form}
          completion={completion}
          saveState={saveState}
          paymentQr={paymentQr}
          onChange={updateField}
          onSubmit={saveSubscription}
        />
      )}
      {activeTab === 'contact' && <ContactTab />}
    </main>
  )
}

function OverviewTab({ onStart }) {
  const [recruiters, setRecruiters] = useState([])
  const [alertState, setAlertState] = useState({ status: 'loading', message: 'Reading today workbook...' })
  const [activeRecruiterIndex, setActiveRecruiterIndex] = useState(0)

  useEffect(() => {
    let isMounted = true

    async function loadRecruiters() {
      try {
        const response = await fetch('/api/todays-recruiters')
        const result = await response.json()
        if (!response.ok) {
          throw new Error(result.message || 'Could not read today recruiter emails.')
        }
        if (!isMounted) {
          return
        }
        setRecruiters(result.recruiters ?? [])
        setActiveRecruiterIndex(0)
        setAlertState({
          status: 'ready',
          message: result.recruiters?.length
            ? `${result.recruiters.length} recruiter emails found today`
            : `No recruiter emails found in ${result.date}.xlsx yet`,
        })
      } catch (error) {
        if (isMounted) {
          setRecruiters([])
          setAlertState({ status: 'error', message: error.message })
        }
      }
    }

    loadRecruiters()
    const refreshTimer = window.setInterval(loadRecruiters, 15000)
    return () => {
      isMounted = false
      window.clearInterval(refreshTimer)
    }
  }, [])

  useEffect(() => {
    if (recruiters.length <= 1) {
      return undefined
    }
    const slideTimer = window.setInterval(() => {
      setActiveRecruiterIndex((current) => (current + 1) % recruiters.length)
    }, 1000)
    return () => window.clearInterval(slideTimer)
  }, [recruiters.length])

  const activeRecruiter = recruiters[activeRecruiterIndex] ?? null

  return (
    <section className="tab-panel overview-layout">
      <div className="overview-copy">
        <p className="section-kicker">Fastest fingers first</p>
        <h2>Fresh LinkedIn recruiter posts, captured before the crowd arrives.</h2>
        <p>
          AI layoffs are making the job market tougher, and fresh opportunities disappear
          fast. Don't worry, our tool finds fresh LinkedIn job posts within 1 minute of
          being posted.
        </p>
        <p>
          It collects recruiter emails from today's LinkedIn posts and helps job seekers
          reach out fast with their resume, tailored email body, and signature.
        </p>
        <p>
          In this market, it's fastest fingers first. The sooner you reach recruiters,
          the better your chance before recruiters inboxes get crowded.
        </p>
        <button type="button" className="primary-action" onClick={onStart}>
          Configure my alerts <ArrowRight size={18} />
        </button>
      </div>

      <div className="alert-preview" aria-label="Recruiter alert preview">
        <div className="preview-header">
          <span>Live alert</span>
          <strong>00:58</strong>
        </div>
        <div className="preview-card">
          <div className="preview-icon">
            <Mail size={19} />
          </div>
          <LiveRecruiterAlert
            recruiter={activeRecruiter}
            index={activeRecruiterIndex}
            total={recruiters.length}
            state={alertState}
          />
        </div>
        <div className="preview-card hot">
          <div className="preview-icon">
            <Search size={19} />
          </div>
          <div>
            <span>Keyword matched</span>
            <strong>Java C2C hiring</strong>
          </div>
        </div>
        <div className="preview-card">
          <div className="preview-icon">
            <Send size={19} />
          </div>
          <div>
            <span>Ready to send</span>
            <strong>Resume + tailored body</strong>
          </div>
        </div>
        <p className="preview-note">Subscribe for daily recruiter email alerts and faster job-search support.</p>
      </div>
    </section>
  )
}

function LiveRecruiterAlert({ recruiter, index, total, state }) {
  if (!recruiter) {
    return (
      <div className="live-alert-copy">
        <span>Recruiter email found</span>
        <strong>{state.message}</strong>
      </div>
    )
  }

  return (
    <div key={`${recruiter.email}-${index}`} className="live-alert-copy live-alert-flash">
      <span>Recruiter email found {total > 1 ? `${index + 1}/${total}` : ''}</span>
      <strong>{recruiter.email}</strong>
      <small>{recruiter.timestamp || 'Timestamp not available'}</small>
    </div>
  )
}

function SubscribeTab({ form, completion, saveState, paymentQr, onChange, onSubmit }) {
  return (
    <section className="tab-panel subscribe-layout">
      <aside className="form-status">
        <p className="section-kicker">Subscription setup</p>
        <h2>Capture everything needed to generate your outreach configuration.</h2>
        <div className="completion">
          <div>
            <span>{completion}%</span>
            <small>required fields ready</small>
          </div>
          <progress value={completion} max="100" />
        </div>
        <ul className="included-list">
          <li><Check size={16} /> Recruiter alert delivery details</li>
          <li><Check size={16} /> LinkedIn search keyword and role preferences</li>
          <li><Check size={16} /> Subject, variants, body, and signature</li>
          <li><Check size={16} /> Resume attachment reference</li>
        </ul>
      </aside>

      <form className="config-form" onSubmit={onSubmit}>
        <fieldset>
          <legend><UserRound size={18} /> Subscriber Details</legend>
          <div className="field-grid">
            <label>
              Full name
              <input name="fullName" value={form.fullName} onChange={onChange} placeholder="Your full name" required />
            </label>
            <label>
              Email ID
            <input name="email" type="email" value={form.email} onChange={onChange} placeholder="you@gmail.com" required />
            </label>
            <label>
              Phone number
            <input name="phone" type="tel" value={form.phone} onChange={onChange} placeholder="+1 555 000 0000" required />
            </label>
            <label>
              LinkedIn profile
              <input name="linkedinUrl" type="url" value={form.linkedinUrl} onChange={onChange} placeholder="https://linkedin.com/in/..." required />
            </label>
          </div>
        </fieldset>

        <fieldset>
          <legend><BriefcaseBusiness size={18} /> Search Preferences</legend>
          <div className="field-grid">
            <label>
              Keyword to search in LinkedIn
              <input name="keyword" value={form.keyword} onChange={onChange} placeholder="Java c2c hiring" required />
            </label>
            <label>
              Target role
              <input name="targetRole" value={form.targetRole} onChange={onChange} placeholder="Java Full Stack Developer" required />
            </label>
            <label>
              Preferred location
              <input name="location" value={form.location} onChange={onChange} placeholder="Remote, Dallas, Austin..." required />
            </label>
            <label>
              Alert frequency
              <select name="frequency" value={form.frequency} onChange={onChange} required>
                {frequencies.map((item) => <option key={item}>{item}</option>)}
              </select>
            </label>
          </div>
          <div className="chips" aria-label="Job type options">
            {jobTypes.map((type) => (
              <label key={type} className={form.jobType === type ? 'chip selected' : 'chip'}>
                <input
                  type="radio"
                  name="jobType"
                  value={type}
                  checked={form.jobType === type}
                  onChange={onChange}
                  required
                />
                {type}
              </label>
            ))}
          </div>
        </fieldset>

        <fieldset>
          <legend><Mail size={18} /> Email Content</legend>
          <label>
            Subject
            <input name="subject" value={form.subject} onChange={onChange} placeholder="Java Developer Available for Immediate Submission" required />
          </label>
          <label>
            Subject variants
            <textarea
              name="subjectVariants"
              value={form.subjectVariants}
              onChange={onChange}
              rows="3"
              placeholder="Add one subject variant per line"
              required
            />
          </label>
          <label>
            Email body
            <textarea
              name="body"
              value={form.body}
              onChange={onChange}
              rows="7"
              placeholder="Hi ${firstname},&#10;&#10;I saw your LinkedIn post and wanted to share my resume..."
              required
            />
          </label>
          <label>
            Signature
            <textarea
              name="signature"
              value={form.signature}
              onChange={onChange}
              rows="4"
              placeholder="Regards,&#10;Your Name&#10;Phone | Email | LinkedIn"
              required
            />
          </label>
        </fieldset>

        <fieldset>
          <legend><Upload size={18} /> Resume & Consent</legend>
          <label className="upload-zone">
            <input name="resumeName" type="file" accept=".pdf,.doc,.docx" onChange={onChange} required />
            <Upload size={20} />
            <span>{form.resumeName || 'Attach resume PDF or DOCX'}</span>
          </label>
          <label className="consent-row">
            <input name="consent" type="checkbox" checked={form.consent} onChange={onChange} required />
            I agree to be contacted about recruiter email alerts and subscription setup.
          </label>
        </fieldset>

        {saveState.message && (
          <p className={`save-message ${saveState.status}`} role="status">
            {saveState.message}
          </p>
        )}

        {paymentQr && (
          <section className="payment-card" aria-label="Subscription activation payment">
            <img src={paymentQr} alt="Random subscription activation QR code" />
            <div>
              <p className="section-kicker">Activate subscription</p>
              <h3>Scan and send us screenshot of Zelle payment to +1 615 960 4713.</h3>
              <p>We will activate your subscription after payment screenshot verification.</p>
            </div>
          </section>
        )}

        <button type="submit" className="primary-action form-action" disabled={saveState.status === 'saving'}>
          {saveState.status === 'saving' ? 'Saving...' : 'Save subscription request'} <ArrowRight size={18} />
        </button>
      </form>
    </section>
  )
}

function ContactTab() {
  return (
    <section className="tab-panel contact-layout">
      <div>
        <p className="section-kicker">Contact us</p>
        <h2>Need help setting up your recruiter alert subscription?</h2>
        <p className="contact-copy">
          Reach out with your target role, resume type, and preferred LinkedIn search keyword.
          We will help shape the configuration so alerts are useful from day one.
        </p>
      </div>

      <div className="contact-cards">
        <a href="tel:+16159604713" className="contact-card">
          <span><Phone size={22} /></span>
          <div>
            <small>Phone</small>
            <strong>+1 615 960 4713</strong>
          </div>
        </a>
        <a href="mailto:kbmaheswarareddy@gmail.com" className="contact-card">
          <span><Mail size={22} /></span>
          <div>
            <small>Email</small>
            <strong>kbmaheswarareddy@gmail.com</strong>
          </div>
        </a>
      </div>
    </section>
  )
}

async function createRandomPaymentQr(email) {
  const activationCode = crypto.randomUUID()
  return QRCode.toDataURL(
    `Zelle payment screenshot required for ${email}. Send screenshot to +1 615 960 4713. Activation code: ${activationCode}`,
    {
      color: {
        dark: '#10212e',
        light: '#ffffff',
      },
      errorCorrectionLevel: 'M',
      margin: 2,
      width: 260,
    },
  )
}

export default App
