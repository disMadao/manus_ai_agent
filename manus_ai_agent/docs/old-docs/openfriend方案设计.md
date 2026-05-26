
之前的叫做loveapp，是恋爱咨询的agent，现在想做一个更加通用的，之前的代码导致输出老是夹杂这一些垃圾信息，需要大改。

## 重点1：
记忆模块，现在是做成的一个advisor的，直接手动初始化的，这样不能使多个agent共享，所以现在把advisor做成一个 Bean，让所有agent共享。

## 重点2：
之前的agent分为普通的对话式的，和仿照 openmanus实现的一个具有一些工具调用能力的 agent，现在spring ai alibaba 框架出的好像可以直接实例化一个 ReAct agent，调研一下，对话一下哪种好。

阿里的ReAct agent：


我的ManusAgent：



## 重点3：
这个可能只能探讨一下，实现起来太复杂而且耗时， 而且不一定能做成。
字节开源了一个 openviking，和我这个记忆系统设计主题差不多，他们可以把那个东西做成一个 openclaw 插件，而且在一个开源 testbed 中测试成绩还不错，我也想做成这样的一个插件。

或者去哪个 testbed 中测试一下这个的效果。