import type { Scenario } from './types';

export const SCENARIOS: Scenario[] = [
  {
    id: 'frontend',
    title: '面试准备',
    tag: 'Web Dev',
    emoji: '💼',
    difficulty: 3,
    description: '技术/行为面试练习。模拟真实 HR 或技术主管的提问风格，锻炼抗压表达。',
    welcomeMessage: 'Hello! Welcome to the interview. To start off, could you please tell me a little bit about yourself and your background in software development?',
    welcomeTranslation: '你好！欢迎参加面试。首先，能否简单介绍一下你自己以及你在软件开发方面的背景？',
    systemPrompt: '你是一位技术面试官，负责评估前端开发候选人。请用专业但友好的方式提问，评估候选人的技术能力和沟通技巧。',
    questions: [
      {
        text: 'That is wonderful. Since you mentioned React, can you explain the critical differences between Server Components and Client Components in React 19, and when to use each?',
        translation: '太赞了。既然你提到了 React，你能解释一下 React 19 中服务端组件 (Server Components) 和客户端组件 (Client Components) 的关键区别，以及何时使用它们吗？',
        keywords: [
          {
            phrase: 'i know react server component',
            suggested: 'I have hands-on experience leveraging React Server Components (RSC) to reduce bundle sizes and speed up initial page load times.',
            explanation: '“手头有实际开发经验 RSC 来有效缩小JS打包体积并提升首屏载入速度” 显得你更有性能敏感度和高级功底。'
          },
          {
            phrase: 'render on server',
            suggested: 'Leveraging server-side rendering patterns to offload rendering logic off the client device, optimizing execution latency.',
            explanation: '使用“服务端渲染范式将渲染算力从客户端卸载” 看起来对分布式系统分流设计极具底气。'
          },
          {
            phrase: 'client component is interactive',
            suggested: 'We strictly designate interactive modules containing state hooks (like useState or useActionState) under client boundaries.',
            explanation: '“当涉及携带状态勾子的交互边界时，严谨限定在客户端侧” 措辞干脆、条理分明。'
          }
        ]
      },
      {
        text: 'Excellent clarity. Now, regarding CSS and design systems, how do you manage utility-first styles in Tailwind CSS to maintain highly modular and customizable responsive UI layers?',
        translation: '极其清晰的表述。那么，有关 CSS 和设计系统，你如何管理 Tailwind CSS 中的实用优先样式，以维护高度模块化、且支持高阶客制化的响应式 UI 界面？',
        keywords: [
          {
            phrase: 'tailwind is easy',
            suggested: 'Tailwind CSS vastly accelerates our design-to-code velocity while maintaining strict stylistic alignment using configured theme tokens.',
            explanation: '建议使用“加速设计到代码的交付流速，同时保持严密的设计符号一致性” 替代 “Tailwind 很简单”。'
          },
          {
            phrase: 'use classnames the normal way',
            suggested: 'I systematically resolve stylistic collisions using tools like tailwind-merge andclsx to maintain clean, dynamic utility combinations.',
            explanation: '建议说：“系统化地采用 tailwind-merge 和 clsx 方案动态处理动态冲突，保证样式清晰高聚合”。'
          }
        ]
      },
      {
        text: 'Perfect. Finally, in high-traffic applications, how do you diagnostic and optimize slow re-renders using standard React dev tools or profiling hooks?',
        translation: '完美。最后一个问题，在高流量应用中，你如何通过内置的 React 开发者工具或分析钩子诊断并优化缓慢的重绘现象？',
        keywords: [
          {
            phrase: 'use memo and callback',
            suggested: 'I strategically introduce memoization with useMemo and useCallback after profiling to prevent heavy overhead and redundant re-computations.',
            explanation: '“在性能探针剖析之后，策略性地引入防抖记忆化手段” 能够展示更敏锐、客观的调优方法论，而非盲目滥用钩子。'
          },
          {
            phrase: 'profiler is good',
            suggested: 'We conduct render profiles during hot interaction paths, identifying hook dependency changes that trigger unexpected cascade updates.',
            explanation: '表示“我们会在热高频交互路径上跑渲染性能测绘，定位导致链式二次更新的罪魁祸首”，极其资深！'
          }
        ]
      }
    ]
  },
  {
    id: 'restaurant',
    title: '餐厅点餐',
    tag: 'Food & Drink',
    emoji: '🍔',
    difficulty: 1,
    description: '生活口语基础。从预定位置、询问菜单到结账，掌握日常出行的必备句型。',
    welcomeMessage: 'Hello! Welcome to Lingo Bistro. I will be your server today. Would you like to start with some drinks or check our specials?',
    welcomeTranslation: '您好！欢迎光临 Lingo 小酒馆。我是您今天的服务员。您想先喝点饮品，还是看看我们的今日特供？',
    systemPrompt: '你是一位餐厅服务员，请用友好专业的态度为顾客提供点餐服务。',
    questions: [
      {
        text: 'Excellent choice. Our chef recommends the pan-seared ribeye steak or the creamy garlic seafood pasta today. What would you like to have for your main course?',
        translation: '优秀的选项。我们的大厨今天推荐香煎肋眼牛排或奶油蒜蓉海鲜面。您的主菜想吃点什么呢？',
        keywords: [
          {
            phrase: 'i want steak',
            suggested: 'I would like to order the chef-recommended pan-seared ribeye steak, cooked medium.',
            explanation: '在点西餐牛排时，直接说 "I want" 稍显生硬，用优雅的 "I would like to order... cooked medium (五分熟)" 更加地道。'
          }
        ]
      },
      {
        text: 'Perfect! And how would you like your steak done? We also offer various delicious sides like truffle fries or roasted asparagus.',
        translation: '完美！您的牛排需要几分熟？我们还提供黑松露薯条或烤芦笋等多种可口配菜。',
        keywords: [
          {
            phrase: 'well done',
            suggested: 'I prefer it medium-rare to maintain the tenderness, accompanied by a side of roasted asparagus.',
            explanation: '若点牛排，一般常选 medium-rare (三分熟) 或 medium (五分熟)，熟度更适口。'
          }
        ]
      }
    ]
  },
  {
    id: 'conference',
    title: '国际会议',
    tag: 'Business',
    emoji: '📢',
    difficulty: 2,
    description: '商务听力与表达。练习在多人会议中陈述观点、提问及应对复杂讨论的技巧。',
    welcomeMessage: "Welcome everyone to our Q2 international business review. Let's discuss our globalization metrics. How did our new regional campaign perform last quarter?",
    welcomeTranslation: '欢迎各位来到我们第二季度的国际业务回顾会议。我们来商讨一下全球化指标。上个季度我们新的区域推广活动表现如何？',
    systemPrompt: '你是一位商务会议主持人，请用专业商务英语引导会议讨论。',
    questions: [
      {
        text: 'That sounds promising. However, our user engagement rate in some markets saw a slight decline. What adjustments should we introduce in the upcoming sprint to optimize this?',
        translation: '这听起来很有前景。然而，我们在某些市场的用户参与率出现了轻微下滑。在接下来的迭代中，我们应该引入哪些调整来优化这一点？',
        keywords: [
          {
            phrase: 'do marketing again',
            suggested: 'We should initiate targeted localized marketing user conversion strategies to dynamically capture active organic users.',
            explanation: '在国际商务会议中，使用“发起针对性的本土化用户转化与拉活策略”能代替通俗表达 “do marketing again”, 商务范十足！'
          }
        ]
      },
      {
        text: 'Great suggestions. Let\'s compile these into our roadmap. Any other concerns or updates regarding our server scaling capability before we wrap up?',
        translation: '极好的建议。我们将把这些整理进我们的路线图。在结束会议前，关于我们服务器扩容能力，大家还有其他疑问或更新汇报吗？',
        keywords: [
          {
            phrase: 'server is good',
            suggested: 'Our current infrastructure scalability demonstrates optimal resilience under high load stress testing.',
            explanation: '“我们的底层基础设施弹性扩缩容特性，在极限高负载压力测试中展现出极佳的稳定韧性”。'
          }
        ]
      }
    ]
  }
];

export const MOCK_RATING_DATABASE = [
  {
    overall: 88,
    accuracy: 85,
    fluency: 90,
    grammar: 82,
    summary: "Your pronunciation is very clear with excellent rhythm and stress on key technical jargon. Intonation flows naturally."
  },
  {
    overall: 82,
    accuracy: 85,
    fluency: 78,
    grammar: 84,
    summary: "Good pace overall. Try to pronounce words like 'developer' and 'optimizing' with slightly more dynamic stress. Excellent use of pauses."
  },
  {
    overall: 89,
    accuracy: 90,
    fluency: 87,
    grammar: 92,
    summary: "Extremely fluent software background pitch. Grammatical structures are highly professional, showcasing advanced sentence structures."
  }
];
