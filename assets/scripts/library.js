const Extractor = java.lang.Class.forName("ext.Extractor", false, Vars.mods.mainLoader())
const ExtractOptions = java.lang.Class.forName("ext.Extractor$ExtractOptions", false, Vars.mods.mainLoader())

const extract = Extractor.getMethod("extract", Mesh, ExtractOptions)
const newOptions = ExtractOptions.getConstructor()

module.exports = function(mesh, options){
    let opts = newOptions.newInstance()
    if(typeof(options) !== 'undefined') Object.assign(opts, options)

    try{
        extract.invoke(null, mesh, opts)
    }catch(e){
        Log.err(e)
    }
}
